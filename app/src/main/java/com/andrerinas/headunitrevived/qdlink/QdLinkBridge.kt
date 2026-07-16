package com.andrerinas.headunitrevived.qdlink

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.andrerinas.headunitrevived.BuildConfig
import com.andrerinas.headunitrevived.utils.AppLog
import org.json.JSONObject
import java.io.OutputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

data class QdTouch(val x: Float, val y: Float, val action: Int)

/** Phone-side QDLink/SSP endpoint. Android Auto access units are passed through unchanged. */
class QdLinkBridge(private val context: Context) {
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var out: OutputStream? = null
    @Volatile private var playing = false
    @Volatile private var port = 0
    @Volatile var width = 1920; private set
    @Volatile var height = 882; private set
    @Volatile var touchWidth = 1920; private set
    @Volatile var touchHeight = 882; private set
    @Volatile private var encodedWidth = 0
    @Volatile private var encodedHeight = 0
    @Volatile private var fps = 30
    @Volatile private var bitRate = 5_080_320
    @Volatile private var interval = 3
    @Volatile private var config: ByteArray? = null
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var configSent = false
    @Volatile private var resendConfig = false
    var onTouch: ((QdTouch) -> Unit)? = null
    var onKeyFrameRequest: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    val isRunning: Boolean
        get() = running.get()

    fun start() {
        if (running.getAndSet(true)) return
        port = ServerSocket(0).use { it.localPort }
        Thread(::discoveryLoop, "qdlink-discovery").start()
        Thread(::serverLoop, "qdlink-mirror").start()
        AppLog.i("QDLink: started mirrorPort=$port")
    }

    fun stop() {
        running.set(false); playing = false
        try { out?.close() } catch (_: Exception) {}
        out = null
    }

    fun sendAccessUnit(data: ByteArray, offset: Int, size: Int, codecConfig: Boolean, keyFrame: Boolean) {
        if (size <= 0) return
        val au = data.copyOfRange(offset, offset + size)
        val normalized = normalizeAnnexB(au)
        normalized.sps?.let {
            sps = it
            parseSpsDimensions(it)?.let { dimensions ->
                encodedWidth = dimensions.first
                encodedHeight = dimensions.second
                AppLog.i("QDLink: Android Auto coded size ${encodedWidth}x${encodedHeight}; car canvas ${width}x${height}")
            }
        }
        normalized.pps?.let { pps = it }
        if (sps != null && pps != null) config = sps!! + pps!!

        val slices = normalized.slices
        if (slices.isEmpty() || !playing || out == null) return
        val isKeyFrame = normalized.keyFrame || keyFrame
        if (!configSent || (isKeyFrame && resendConfig)) {
            val codecData = config
            if (codecData == null) {
                AppLog.w("QDLink: dropping video until both SPS and PPS are available")
                return
            }
            sendVideo(codecData, "CONFIG")
            configSent = true
            resendConfig = false
        }
        sendVideo(slices, if (isKeyFrame) "IDR" else "P")
    }

    private data class NormalizedAu(
        val sps: ByteArray?, val pps: ByteArray?, val slices: ByteArray, val keyFrame: Boolean
    )

    private class BitReader(private val data: ByteArray, offset: Int) {
        private var bit = offset * 8
        fun one(): Int = if (bit / 8 >= data.size) 0 else
            (data[bit / 8].toInt() shr (7 - bit++ % 8)) and 1
        fun bits(count: Int): Int { var value = 0; repeat(count) { value = value shl 1 or one() }; return value }
        fun ue(): Int { var zeros = 0; while (one() == 0 && zeros < 31) zeros++; return if (zeros == 0) 0 else (1 shl zeros) - 1 + bits(zeros) }
    }

    private fun parseSpsDimensions(sps: ByteArray): Pair<Int, Int>? = try {
        // normalizeAnnexB always emits a four-byte start code; byte 4 is NAL header.
        val r = BitReader(sps, 4)
        r.bits(8)
        val profile = r.bits(8)
        r.bits(16); r.ue()
        var chroma = 1
        if (profile in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
            chroma = r.ue()
            if (chroma == 3) r.one()
            r.ue(); r.ue(); r.one()
            if (r.one() == 1) repeat(if (chroma != 3) 8 else 12) { index ->
                if (r.one() == 1) {
                    var last = 8; var next = 8
                    repeat(if (index < 6) 16 else 64) {
                        if (next != 0) next = (last + r.ue() + 256) % 256
                        if (next != 0) last = next
                    }
                }
            }
        }
        r.ue()
        when (r.ue()) { 0 -> r.ue(); 1 -> { r.one(); r.ue(); r.ue(); repeat(r.ue()) { r.ue() } } }
        r.ue(); r.one()
        val widthMbs = r.ue() + 1
        val heightMap = r.ue() + 1
        val frameMbsOnly = r.one()
        if (frameMbsOnly == 0) r.one()
        r.one()
        var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
        if (r.one() == 1) { cropLeft = r.ue(); cropRight = r.ue(); cropTop = r.ue(); cropBottom = r.ue() }
        val cropUnitX = if (chroma == 0) 1 else 2
        val cropUnitY = if (chroma == 0) 2 - frameMbsOnly else 2 * (2 - frameMbsOnly)
        val w = widthMbs * 16 - (cropLeft + cropRight) * cropUnitX
        val h = heightMap * 16 * (2 - frameMbsOnly) - (cropTop + cropBottom) * cropUnitY
        if (w > 0 && h > 0) w to h else null
    } catch (_: Exception) { null }

    /**
     * Android Auto may combine AUD/SEI/SPS/PPS/IDR in one access unit. QDLink's
     * automotive decoder requires SPS+PPS as one standalone frame and subsequent
     * frames containing only VCL NAL units (types 1 and 5).
     */
    private fun normalizeAnnexB(au: ByteArray): NormalizedAu {
        data class Nal(val start: Int, val header: Int, val end: Int)
        fun startCodeAt(pos: Int): Int {
            if (pos + 3 > au.size || au[pos] != 0.toByte() || au[pos + 1] != 0.toByte()) return 0
            if (au[pos + 2] == 1.toByte()) return 3
            return if (pos + 4 <= au.size && au[pos + 2] == 0.toByte() && au[pos + 3] == 1.toByte()) 4 else 0
        }
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < au.size - 2) {
            val length = startCodeAt(i)
            if (length > 0) { starts += i to length; i += length } else i++
        }
        // Preserve unknown/non-Annex-B input rather than silently blacking video.
        if (starts.isEmpty()) return NormalizedAu(null, null, au, false)
        val nals = starts.mapIndexedNotNull { index, entry ->
            val end = starts.getOrNull(index + 1)?.first ?: au.size
            val header = entry.first + entry.second
            if (header < end) Nal(entry.first, header, end) else null
        }
        var foundSps: ByteArray? = null
        var foundPps: ByteArray? = null
        var key = false
        val vcl = ArrayList<ByteArray>()
        for (nal in nals) {
            val type = au[nal.header].toInt() and 0x1f
            // Normalize to the four-byte start codes emitted by MediaCodec/QDLink.
            val bytes = byteArrayOf(0, 0, 0, 1) + au.copyOfRange(nal.header, nal.end)
            when (type) {
                7 -> foundSps = bytes
                8 -> foundPps = bytes
                1 -> vcl += bytes
                5 -> { key = true; vcl += bytes }
                // 6=SEI and 9=AUD are intentionally excluded.
            }
        }
        val sliceBytes = ByteArray(vcl.sumOf { it.size })
        var destination = 0
        vcl.forEach { nal -> nal.copyInto(sliceBytes, destination); destination += nal.size }
        return NormalizedAu(foundSps, foundPps, sliceBytes, key)
    }

    private fun discoveryLoop() {
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(P.UDP_IN))
                socket.soTimeout = 1000
                val packet = DatagramPacket(ByteArray(2048), 2048)
                while (running.get()) try {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    if (!msg.contains("Connect_Broadcast")) continue
                    val ack = P.broadcastAck(port)
                    DatagramSocket().use { it.send(DatagramPacket(ack, ack.size, packet.address, P.UDP_OUT)) }
                } catch (_: SocketTimeoutException) {}
            }
        } catch (e: Exception) { if (running.get()) AppLog.e("QDLink discovery failed: ${e.message}") }
    }

    private fun serverLoop() {
        try {
            ServerSocket(port).use { server ->
                server.soTimeout = 1000
                while (running.get()) try { session(server.accept()) } catch (_: SocketTimeoutException) {}
            }
        } catch (e: Exception) { if (running.get()) AppLog.e("QDLink server failed: ${e.message}") }
    }

    private fun session(socket: Socket) {
        socket.tcpNoDelay = true
        try {
            out = socket.getOutputStream(); send(P.appStatus())
            Thread({ while (running.get() && out != null) { send(P.control("HEARTBEAT", null, 1)); Thread.sleep(3000) } }, "qdlink-heartbeat").start()
            Thread({ while (running.get() && out != null) { send(P.whitelist()); Thread.sleep(1000) } }, "qdlink-whitelist").start()
            val pending = ArrayList<Byte>(); val tmp = ByteArray(65536); val input = socket.getInputStream()
            while (running.get()) {
                val n = input.read(tmp); if (n <= 0) break
                for (i in 0 until n) pending.add(tmp[i])
                parse(pending)
            }
        } catch (e: Exception) { if (running.get()) AppLog.i("QDLink session ended: ${e.message}") }
        finally { playing = false; configSent = false; out = null; try { socket.close() } catch (_: Exception) {}; onDisconnected?.invoke() }
    }

    private fun parse(buf: ArrayList<Byte>) {
        while (buf.size >= 16) {
            if (buf[0] != '5'.code.toByte() || buf[1] != 'A'.code.toByte() || buf[2] != '5'.code.toByte() || buf[3] != 'A'.code.toByte()) { buf.removeAt(0); continue }
            val header = ByteArray(16) { buf[it] }; val total = P.int(header, 4)
            if (total < 16 || total > 8 * 1024 * 1024) { buf.removeAt(0); continue }
            if (buf.size < total) return
            val body = ByteArray(total - 16) { buf[it + 16] }; repeat(total) { buf.removeAt(0) }
            if ((header[10].toInt() and 255) == 2) parseTouch(body) else try { dispatch(JSONObject(String(body))) } catch (_: Exception) {}
        }
    }

    private fun dispatch(json: JSONObject) {
        val para = json.optJSONObject("PARA")
        when (json.optString("CMD")) {
            "CAR_INFO" -> {
                width = para?.optInt("CarWidth", 1920)?.takeIf { it > 0 } ?: 1920
                height = para?.optInt("CarHeight", 882)?.takeIf { it > 0 } ?: 882
                val phone = realDisplaySize()
                val mirror = fitMirrorSize(width, height, phone.first, phone.second)
                touchWidth = phone.first
                touchHeight = phone.second
                AppLog.i("QDLink: phone=${phone.first}x${phone.second} mirror=${mirror.first}x${mirror.second} car=${width}x${height}")
                send(P.control("PHONE_INFO", P.phoneInfo(
                    phone.first, phone.second, mirror.first, mirror.second,
                    width, height, para?.optInt("MirrorTypeReq", 0) ?: 0
                ), 1))
                send(P.videoSupport()); send(P.whitelist())
            }
            "VIDEO_SUP_REQ" -> send(P.videoSupport())
            "VIDEO_ARGS" -> { width = para?.optInt("Width", width) ?: width; height = para?.optInt("Height", height) ?: height; fps = para?.optInt("FrameRate", 30) ?: 30; bitRate = para?.optInt("BitRate", bitRate) ?: bitRate; interval = para?.optInt("FrameInterval", 3) ?: 3 }
            "HEARTBEAT" -> send(P.control("HEARTBEAT", null, 1))
            "VIDEO_CTRL" -> { playing = para?.optInt("PlayStatus", 0) == 1; if (playing) { configSent = false; resendConfig = true; onKeyFrameRequest?.invoke() } }
            "KEY_FRAME_REQ" -> { resendConfig = true; onKeyFrameRequest?.invoke() }
            "DISCONNECT_REQ" -> send(P.control("DISCONNECT_RSP"))
        }
    }

    private fun parseTouch(b: ByteArray) {
        if (b.size < 15) return
        val flag = P.int(b, 0); val action = when (flag) { 0 -> 0; 2 -> 2; 1 -> 1; else -> return }
        onTouch?.invoke(QdTouch(P.float(b, 7), P.float(b, 11), action))
    }

    private fun realDisplaySize(): Pair<Int, Int> {
        val point = Point()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealSize(point)
        return maxOf(point.x, point.y).coerceAtLeast(1) to
            minOf(point.x, point.y).coerceAtLeast(1)
    }

    private fun fitMirrorSize(carWidth: Int, carHeight: Int, phoneWidth: Int, phoneHeight: Int): Pair<Int, Int> {
        val carAspect = carWidth.toDouble() / carHeight
        val phoneAspect = phoneWidth.toDouble() / phoneHeight
        val raw = if (carAspect > phoneAspect) {
            (carHeight * phoneAspect).toInt() to carHeight
        } else {
            carWidth to (carWidth / phoneAspect).toInt()
        }
        fun even(value: Int) = ((value + 1) and -2).coerceAtLeast(2)
        return even(raw.first) to even(raw.second)
    }

    private var videoWireCount = 0L
    private fun sendVideo(au: ByteArray, kind: String) {
        val packet = P.video(
            au,
            encodedWidth.takeIf { it > 0 } ?: width,
            encodedHeight.takeIf { it > 0 } ?: height,
            fps, bitRate, interval
        )
        val declared = P.int(packet, 4)
        if (declared < 48 || declared > packet.size) {
            AppLog.e("QDLink wire: refusing $kind invalid declared=$declared allocated=${packet.size}")
            return
        }
        // Wi-Fi must end exactly at the declared frame length. Never transmit a
        // padded backing-array tail (USB alone uses 512-byte allocation alignment).
        synchronized(writeLock) {
            try {
                out?.write(packet, 0, declared)
                out?.flush()
            } catch (_: Exception) {}
        }
        videoWireCount++
        if (kind != "P" || videoWireCount % fps.coerceAtLeast(1) == 0L) {
            AppLog.i("QDLink wire: $kind declared=$declared allocated=${packet.size} transmitted=$declared payload=${au.size}")
        }
    }
    private fun send(bytes: ByteArray) = synchronized(writeLock) { try { out?.write(bytes); out?.flush() } catch (_: Exception) {} }

    private object P {
        const val UDP_IN = 18463; const val UDP_OUT = 18464
        private val ctrl = "5A5A".toByteArray(); private val bin = "!BIN".toByteArray()
        fun put(v: Int, b: ByteArray, o: Int) { ByteBuffer.wrap(b, o, 4).order(ByteOrder.BIG_ENDIAN).putInt(v) }
        fun short(v: Int, b: ByteArray, o: Int) { ByteBuffer.wrap(b, o, 2).order(ByteOrder.BIG_ENDIAN).putShort(v.toShort()) }
        fun int(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.BIG_ENDIAN).int
        fun float(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.BIG_ENDIAN).float
        private fun hex(v: Int, n: Int) = Integer.toHexString(v).uppercase().padStart(n, '0')
        fun broadcastAck(port: Int): ByteArray { val j = JSONObject().put("ControlPort",0).put("MirrorPort",port).put("AudioPort",0).put("OS",0).put("DeviceName",Build.MODEL).put("DeviceUUID",Build.FINGERPRINT.take(16)).put("DeviceFeature",JSONObject().put("PassistMobileNum","")).toString(); return ("QDrive_SSPLink_UDP_MSG"+hex(45+j.length,4)+hex(13,2)+"Broadcast_ACK"+hex(j.length,4)+j).toByteArray() }
        fun appStatus(): ByteArray { val h=ByteArray(512); bin.copyInto(h); put(0,h,4); put(512,h,8); put(512,h,12); put(64,h,16); put(128,h,20); put(128,h,24); put(2,h,28); for(i in 0..31)h[32+i]=(i+32).toByte(); put(1,h,68); put(1,h,72); put(Build.VERSION.SDK_INT,h,76); put(2,h,80); return h }
        fun control(cmd:String, para:JSONObject?=null, flag:Int=0):ByteArray { val j=JSONObject().put("CMD",cmd); if(para!=null)j.put("PARA",para); val body=j.toString().toByteArray(); val h=ByteArray(16);ctrl.copyInto(h);put(16+body.size,h,4);h[13]=flag.toByte();return h+body }
        fun whitelist():ByteArray { val body=JSONObject().put("AppID","Mirror").put("FunctionID","WhitelistAppOn").put("Para",JSONObject().put("WhitelistAppOn",1)).toString().toByteArray();val h=ByteArray(16);ctrl.copyInto(h);put(16+body.size,h,4);h[10]=13;h[13]=1;return h+body }
        fun videoSupport()=control("VIDEO_SUP_RSP",JSONObject().put("VideoFormat",3).put("VideoSupport",1),1)
        fun phoneInfo(phoneW:Int,phoneH:Int,mirrorW:Int,mirrorH:Int,carW:Int,carH:Int,mt:Int)=JSONObject().put("PhoneName",Build.MODEL).put("PhoneUUID",Build.FINGERPRINT.take(16)).put("PhoneBrand",Build.MANUFACTURER).put("PhoneModel",Build.MODEL).put("Version",BuildConfig.VERSION_NAME).put("Platform",0).put("PlatformVersion",Build.VERSION.SDK_INT.toString()).put("PhoneWidth",phoneW).put("PhoneHeight",phoneH).put("MirrorWidth",mirrorW).put("MirrorHeight",mirrorH).put("PhoneWidthInApp",carW).put("PhoneHeightInApp",carH).put("MirrorWidthInApp",carW).put("MirrorHeightInApp",carH).put("MirrorTypeSupport",mt).put("PhoneSystemTime",System.currentTimeMillis()).put("PhoneFeature",JSONObject().put("PassistMobileNum",""))
        fun video(au:ByteArray,w:Int,h:Int,fps:Int,br:Int,fi:Int):ByteArray { val d=ByteArray(32);short(32,d,0);d[2]=1;put(w,d,4);put(h,d,8);d[15]=3;put(fps,d,16);put(br,d,20);put(fi,d,24);d[28]=2;val a=ByteArray(16);ctrl.copyInto(a);put(48+au.size,a,4);short(32,a,8);a[10]=1;a[13]=2;return a+d+au }
    }
}
