package com.andrerinas.headunitrevived.qdlink

import android.os.Build
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
class QdLinkBridge {
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var out: OutputStream? = null
    @Volatile private var playing = false
    @Volatile private var port = 0
    @Volatile var width = 1920; private set
    @Volatile var height = 882; private set
    @Volatile private var fps = 30
    @Volatile private var bitRate = 5_080_320
    @Volatile private var interval = 3
    @Volatile private var config: ByteArray? = null
    @Volatile private var resendConfig = false
    var onTouch: ((QdTouch) -> Unit)? = null
    var onKeyFrameRequest: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

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
        if (codecConfig) config = au
        if (!playing || out == null) return
        if (keyFrame && resendConfig) {
            config?.let(::sendVideo)
            resendConfig = false
        }
        sendVideo(au)
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
        finally { playing = false; out = null; try { socket.close() } catch (_: Exception) {}; onDisconnected?.invoke() }
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
                send(P.control("PHONE_INFO", P.phoneInfo(width, height, para?.optInt("MirrorTypeReq", 0) ?: 0), 1))
                send(P.videoSupport()); send(P.whitelist())
            }
            "VIDEO_SUP_REQ" -> send(P.videoSupport())
            "VIDEO_ARGS" -> { width = para?.optInt("Width", width) ?: width; height = para?.optInt("Height", height) ?: height; fps = para?.optInt("FrameRate", 30) ?: 30; bitRate = para?.optInt("BitRate", bitRate) ?: bitRate; interval = para?.optInt("FrameInterval", 3) ?: 3 }
            "VIDEO_CTRL" -> { playing = para?.optInt("PlayStatus", 0) == 1; if (playing) { resendConfig = true; onKeyFrameRequest?.invoke() } }
            "KEY_FRAME_REQ" -> { resendConfig = true; onKeyFrameRequest?.invoke() }
            "DISCONNECT_REQ" -> send(P.control("DISCONNECT_RSP"))
        }
    }

    private fun parseTouch(b: ByteArray) {
        if (b.size < 15) return
        val flag = P.int(b, 0); val action = when (flag) { 0 -> 0; 2 -> 2; 1 -> 1; else -> return }
        onTouch?.invoke(QdTouch(P.float(b, 7), P.float(b, 11), action))
    }

    private fun sendVideo(au: ByteArray) = send(P.video(au, width, height, fps, bitRate, interval))
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
        fun phoneInfo(w:Int,h:Int,mt:Int)=JSONObject().put("PhoneName",Build.MODEL).put("PhoneUUID",Build.FINGERPRINT.take(16)).put("PhoneBrand",Build.MANUFACTURER).put("PhoneModel",Build.MODEL).put("Version",BuildConfig.VERSION_NAME).put("Platform",0).put("PlatformVersion",Build.VERSION.SDK_INT.toString()).put("PhoneWidth",w).put("PhoneHeight",h).put("MirrorWidth",w).put("MirrorHeight",h).put("PhoneWidthInApp",w).put("PhoneHeightInApp",h).put("MirrorWidthInApp",w).put("MirrorHeightInApp",h).put("MirrorTypeSupport",mt).put("PhoneSystemTime",System.currentTimeMillis()).put("PhoneFeature",JSONObject().put("PassistMobileNum",""))
        fun video(au:ByteArray,w:Int,h:Int,fps:Int,br:Int,fi:Int):ByteArray { val d=ByteArray(32);short(32,d,0);d[2]=1;put(w,d,4);put(h,d,8);d[15]=3;put(fps,d,16);put(br,d,20);put(fi,d,24);d[28]=2;val a=ByteArray(16);ctrl.copyInto(a);put(48+au.size,a,4);short(32,a,8);a[10]=1;a[13]=2;return a+d+au }
    }
}
