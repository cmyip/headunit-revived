package com.andrerinas.headunitrevived.qdlink

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.BuildConfig
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog
import org.json.JSONObject
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
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
    private val sessionLock = Any()
    @Volatile private var out: OutputStream? = null
    @Volatile private var activeConnection: Closeable? = null
    // Wired QDLink is Android Open Accessory: same frames as Wi-Fi, but every
    // transfer is a whole number of 512-byte blocks in both directions.
    @Volatile private var usbTransport = false
    @Volatile private var received = false
    // Session diagnostics, reported every few seconds and when the session ends.
    @Volatile private var lastRxAt = 0L
    @Volatile private var writeStartedAt = 0L
    @Volatile private var rxFrames = 0
    @Volatile private var txFrames = 0
    @Volatile private var txFailures = 0
    @Volatile private var videoSent = 0
    @Volatile private var videoDropped = 0
    @Volatile private var noPermissionLogged = false
    // The head unit stops listening about 18 seconds after it asks for video if
    // none arrives, and nothing in the protocol tells it to keep waiting. Until
    // Android Auto delivers frames, a locally encoded still picture stands in.
    private val videoLock = Any()
    @Volatile private var placeholder: Placeholder? = null
    @Volatile private var androidAutoLive = false
    private var videoSource = SOURCE_NONE
    private var awaitingKeyFrame = false
    // /dev/usb_accessory is exclusive-open. Reopening a detached accessory blocks
    // forever in read() and stops Android from starting the next accessory session,
    // so each attach may be opened once: re-armed when the accessory list empties
    // or Android reports a freshly configured accessory.
    @Volatile private var usbArmed = true
    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // Ignore the "configured" broadcast that trails our own open of this attach.
            if (out == null && intent.getBooleanExtra("accessory", false) &&
                intent.getBooleanExtra("configured", false)) usbArmed = true
        }
    }
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
    @Volatile private var negotiatedOrientation = P.ORIENTATION_LANDSCAPE
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

    /**
     * True while the head unit is answering over the cable. An open accessory is
     * not enough: the head unit can stop listening without the link going down,
     * so require one of its three-second heartbeats to be recent.
     */
    val isUsbConnected: Boolean
        get() = usbTransport && out != null &&
            android.os.SystemClock.elapsedRealtime() - lastRxAt < USB_ALIVE_MS

    /** One-line state for the log: what the USB menu sees when it is tapped. */
    fun describeState(): String {
        val idle = if (lastRxAt == 0L) -1 else android.os.SystemClock.elapsedRealtime() - lastRxAt
        return "running=${running.get()} session=${if (out == null) "none" else if (usbTransport) "usb" else "wifi"} " +
            "accessory=${usbAccessory() != null} armed=$usbArmed playing=$playing lastRxMsAgo=$idle " +
            "rx=$rxFrames tx=$txFrames txFail=$txFailures video=$videoSent dropped=$videoDropped " +
            "source=${sourceName(videoSource)} aaLive=$androidAutoLive"
    }

    /** The attached QDLink head unit, while the phone is in USB accessory mode for one. */
    fun usbAccessory(): UsbAccessory? = try {
        (context.getSystemService(Context.USB_SERVICE) as? UsbManager)?.accessoryList?.firstOrNull {
            it.manufacturer == P.USB_MANUFACTURER && it.model == P.USB_MODEL
        }
    } catch (_: Exception) { null }

    /**
     * Asked for by the USB menu or a cable attach. With no session, let the
     * accessory loop open the head unit again. With a session the head unit has
     * stopped answering, announce ourselves again on the same link: the accessory
     * cannot be reopened while its blocked read is still pending.
     */
    fun connectUsb() {
        AppLog.i("QDLink: connectUsb requested; ${describeState()}")
        if (out == null) usbArmed = true
        else if (usbTransport && !isUsbConnected) {
            AppLog.i("QDLink: USB session is silent, sending AppStatus again")
            Thread({ send(P.appStatus()) }, "qdlink-reannounce").start()
        }
    }

    /** Result target for UsbManager.requestPermission(): starts the cable session once granted. */
    fun usbPermissionIntent(): PendingIntent {
        val intent = Intent(context, AapService::class.java).setAction(AapService.ACTION_QDLINK_USB)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(context, 0, intent, flags)
        else PendingIntent.getService(context, 0, intent, flags)
    }

    fun start() {
        if (running.getAndSet(true)) return
        port = ServerSocket(0).use { it.localPort }
        Thread(::discoveryLoop, "qdlink-discovery").start()
        Thread(::serverLoop, "qdlink-mirror").start()
        val usbState = IntentFilter("android.hardware.usb.action.USB_STATE")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbStateReceiver, usbState, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(usbStateReceiver, usbState)
            }
        } catch (e: Exception) { AppLog.e("QDLink: USB state receiver failed: ${e.message}") }
        Thread(::usbAccessoryLoop, "qdlink-usb-accessory").start()
        AppLog.i("QDLink: started mirrorPort=$port")
    }

    fun stop() {
        running.set(false); playing = false
        stopPlaceholder()
        try { context.unregisterReceiver(usbStateReceiver) } catch (_: Exception) {}
        try { activeConnection?.close() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        out = null
    }

    fun sendAccessUnit(data: ByteArray, offset: Int, size: Int, codecConfig: Boolean, keyFrame: Boolean) =
        forward(SOURCE_ANDROID_AUTO, data, offset, size, keyFrame)

    private fun forward(source: Int, data: ByteArray, offset: Int, size: Int, keyFrame: Boolean) = synchronized(videoLock) {
        if (size <= 0) return
        if (source == SOURCE_ANDROID_AUTO) androidAutoLive = true
        // The placeholder stands in only while Android Auto is not connected. Once
        // Android Auto has delivered a frame it owns the stream until it disconnects:
        // it sends nothing while its picture is unchanged, so a quiet spell is normal.
        else if (androidAutoLive) return
        if (source != videoSource) {
            AppLog.i("QDLink: video source ${sourceName(videoSource)} -> ${sourceName(source)}")
            videoSource = source
            // A different encoder: its parameter sets and a fresh key frame must
            // reach the head unit before any of its other frames.
            sps = null; pps = null; config = null
            configSent = false; awaitingKeyFrame = true
            if (source == SOURCE_ANDROID_AUTO && playing) onKeyFrameRequest?.invoke()
        }
        val au = data.copyOfRange(offset, offset + size)
        val normalized = normalizeAnnexB(au)
        normalized.sps?.let {
            sps = it
            parseSpsDimensions(it)?.let { dimensions ->
                encodedWidth = dimensions.first
                encodedHeight = dimensions.second
                AppLog.i("QDLink: ${sourceName(source)} coded size ${encodedWidth}x${encodedHeight}; car canvas ${width}x${height}")
            }
        }
        normalized.pps?.let { pps = it }
        if (sps != null && pps != null) config = sps!! + pps!!

        val slices = normalized.slices
        if (slices.isEmpty()) return
        if (!playing || out == null) {
            if (videoDropped++ % 120 == 0) AppLog.i(
                "QDLink: dropping ${sourceName(source)} video (dropped=$videoDropped) playing=$playing session=${out != null}")
            return
        }
        val isKeyFrame = normalized.keyFrame || keyFrame
        if (awaitingKeyFrame && !isKeyFrame) return
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
        awaitingKeyFrame = false
        sendVideo(slices, if (isKeyFrame) "IDR" else "P")
    }

    /** True once Android Auto video has reached this bridge on the current connection. */
    val hasAndroidAutoVideo: Boolean
        get() = androidAutoLive

    /** Android Auto disconnected: the placeholder may take the stream back. */
    fun androidAutoStopped() {
        if (androidAutoLive) AppLog.i("QDLink: Android Auto stopped, placeholder may resume")
        androidAutoLive = false
    }

    private fun sourceName(source: Int) = when (source) {
        SOURCE_ANDROID_AUTO -> "Android Auto"
        SOURCE_PLACEHOLDER -> "placeholder"
        else -> "none"
    }

    private fun startPlaceholder() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || placeholder != null) return
        try {
            placeholder = Placeholder(width, height)
            AppLog.i("QDLink: placeholder video started ${width}x${height}")
        } catch (e: Exception) { AppLog.e("QDLink: placeholder video failed to start: $e") }
    }

    private fun stopPlaceholder() {
        placeholder?.let { it.stop(); AppLog.i("QDLink: placeholder video stopped") }
        placeholder = null
    }

    /** Still "waiting" picture encoded as H.264, so the head unit always has a stream. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private inner class Placeholder(private val w: Int, private val h: Int) {
        private val codec = MediaCodec.createEncoderByType("video/avc")
        private val surface: Surface
        @Volatile private var alive = true
        private var parameterSets: ByteArray? = null

        init {
            val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            Thread(::drawLoop, "qdlink-placeholder-draw").start()
            Thread(::drainLoop, "qdlink-placeholder-encode").start()
        }

        private fun drawLoop() {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = h / 14f
            }
            val text = context.getString(R.string.qdlink_waiting_for_video)
            try {
                while (alive) {
                    val canvas = surface.lockHardwareCanvas()
                    canvas.drawColor(Color.rgb(16, 20, 24))
                    canvas.drawText(text, w / 2f, h / 2f, paint)
                    surface.unlockCanvasAndPost(canvas)
                    Thread.sleep(500)
                }
            } catch (e: Exception) { if (alive) AppLog.e("QDLink: placeholder drawing failed: $e") }
        }

        private fun drainLoop() {
            val info = MediaCodec.BufferInfo()
            try {
                while (alive) {
                    val index = codec.dequeueOutputBuffer(info, 100_000)
                    if (index < 0) continue
                    val bytes = ByteArray(info.size)
                    codec.getOutputBuffer(index)?.apply { position(info.offset); get(bytes) }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { parameterSets = bytes; continue }
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    // The encoder emits SPS/PPS once; repeat them on every key frame so
                    // the stream can be picked up again after Android Auto goes away.
                    val unit = if (key) (parameterSets ?: ByteArray(0)) + bytes else bytes
                    forward(SOURCE_PLACEHOLDER, unit, 0, unit.size, key)
                }
            } catch (e: Exception) { if (alive) AppLog.e("QDLink: placeholder encoder failed: $e") }
        }

        fun stop() {
            alive = false
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { surface.release() } catch (_: Exception) {}
        }
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
        session(socket.getInputStream(), socket.getOutputStream(), socket, false)
    }

    private fun session(input: InputStream, output: OutputStream, connection: Closeable, usb: Boolean) {
        synchronized(sessionLock) {
            // One head unit at a time, whichever transport reached us first.
            if (out != null) { try { connection.close() } catch (_: Exception) {}; return }
            received = false
            lastRxAt = 0L; writeStartedAt = 0L
            rxFrames = 0; txFrames = 0; txFailures = 0; videoSent = 0; videoDropped = 0
            // Whichever encoder speaks first on this session must lead with a key frame.
            synchronized(videoLock) { videoSource = SOURCE_NONE }
            usbTransport = usb
            activeConnection = connection
            out = output
        }
        var endReason = "stopped"
        try {
            AppLog.i("QDLink: session started over ${if (usb) "USB" else "Wi-Fi"}")
            send(P.appStatus())
            Thread({ while (running.get() && activeConnection === connection) { send(P.control("HEARTBEAT", null, 1)); Thread.sleep(3000) } }, "qdlink-heartbeat").start()
            Thread({ while (running.get() && activeConnection === connection) { send(P.whitelist()); Thread.sleep(1000) } }, "qdlink-whitelist").start()
            if (usb) Thread({
                // The original app only announces itself about six seconds after the
                // cable handshake. Repeat AppStatus from then on until the car answers.
                try {
                    Thread.sleep(6000)
                    while (running.get() && activeConnection === connection && !received) {
                        send(P.appStatus()); Thread.sleep(3000)
                    }
                } catch (_: InterruptedException) {}
            }, "qdlink-appstatus").start()
            // The head unit writes whole 512-byte blocks, which equal the high-speed
            // bulk packet size, so a larger read request never completes: the USB
            // controller keeps waiting for a short packet. Ask for one block at a time.
            Thread({
                try {
                    while (running.get() && activeConnection === connection) {
                        Thread.sleep(5000)
                        val now = android.os.SystemClock.elapsedRealtime()
                        val stalled = writeStartedAt.let { if (it == 0L) 0 else now - it }
                        AppLog.i("QDLink status: ${describeState()}" +
                            if (stalled > 2000) " WRITE STALLED ${stalled}ms (head unit not reading)" else "")
                    }
                } catch (_: InterruptedException) {}
            }, "qdlink-status").start()
            val pending = ArrayList<Byte>(); val tmp = ByteArray(if (usb) 512 else 65536)
            while (running.get()) {
                val n = input.read(tmp)
                if (n <= 0) { endReason = "read returned $n"; break }
                if (!received) AppLog.i("QDLink: first $n bytes from head unit: ${hex(tmp, minOf(n, 48))}")
                received = true
                lastRxAt = android.os.SystemClock.elapsedRealtime()
                for (i in 0 until n) pending.add(tmp[i])
                parse(pending)
            }
        } catch (e: Exception) { endReason = e.toString() }
        finally {
            AppLog.i("QDLink: session over ${if (usb) "USB" else "Wi-Fi"} ended ($endReason); ${describeState()}")
            synchronized(sessionLock) {
                if (activeConnection === connection) { activeConnection = null; out = null; usbTransport = false }
            }
            playing = false; configSent = false
            stopPlaceholder()
            try { connection.close() } catch (_: Exception) {}
            onDisconnected?.invoke()
        }
    }

    private fun usbAccessoryLoop() {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        while (running.get()) {
            try {
                val accessory = usbAccessory()
                if (accessory == null) { usbArmed = true; noPermissionLogged = false }
                // Android grants permission when the user picks this app for the
                // accessory through the manifest's USB_ACCESSORY_ATTACHED filter.
                else if (usbArmed && out == null && !manager.hasPermission(accessory)) {
                    if (!noPermissionLogged) {
                        noPermissionLogged = true
                        AppLog.w("QDLink: USB accessory attached but permission not granted yet")
                    }
                }
                else if (usbArmed && out == null) {
                    usbArmed = false
                    val descriptor = manager.openAccessory(accessory)
                    if (descriptor != null) {
                        AppLog.i("QDLink: USB accessory ${accessory.manufacturer}/${accessory.model}/${accessory.version}")
                        val connection = UsbConnection(descriptor)
                        session(connection.input, connection.output, connection, true)
                    } else AppLog.e("QDLink: openAccessory returned null (already open elsewhere?)")
                }
            } catch (e: Exception) { if (running.get()) AppLog.e("QDLink USB failed: ${e.message}") }
            try { Thread.sleep(500) } catch (_: InterruptedException) { return }
        }
    }

    private class UsbConnection(private val descriptor: ParcelFileDescriptor) : Closeable {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        override fun close() {
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
            try { descriptor.close() } catch (_: Exception) {}
        }
    }

    private fun parse(buf: ArrayList<Byte>) {
        while (buf.size >= 16) {
            if (buf[0] != '5'.code.toByte() || buf[1] != 'A'.code.toByte() || buf[2] != '5'.code.toByte() || buf[3] != 'A'.code.toByte()) { buf.removeAt(0); continue }
            val header = ByteArray(16) { buf[it] }; val total = P.int(header, 4)
            if (total < 16 || total > 8 * 1024 * 1024) { buf.removeAt(0); continue }
            if (buf.size < total) return
            val body = ByteArray(total - 16) { buf[it + 16] }; repeat(total) { buf.removeAt(0) }
            rxFrames++
            if ((header[10].toInt() and 255) == 2) parseTouch(body) else try { dispatch(JSONObject(String(body))) }
            catch (e: Exception) { AppLog.w("QDLink rx: unparsed frame type=${header[10].toInt() and 255} len=${body.size}: ${String(body).take(120)}") }
        }
    }

    private fun dispatch(json: JSONObject) {
        val para = json.optJSONObject("PARA")
        val cmd = json.optString("CMD")
        // Heartbeats arrive every three seconds; everything else is worth a line.
        if (cmd != "HEARTBEAT") AppLog.i("QDLink rx: ${json.toString().take(400)}")
        when (cmd) {
            "CAR_INFO" -> {
                width = para?.optInt("CarWidth", 1920)?.takeIf { it > 0 } ?: 1920
                height = para?.optInt("CarHeight", 882)?.takeIf { it > 0 } ?: 882
                // This bridge represents the forwarded AA surface, not the
                // physical Android display running headunit-revived. Advertise
                // the QDLink canvas so returned touch uses that same space.
                val phone = if (App.provide(context).settings.qdLinkActiveAreaMargins) {
                    width to height
                } else realDisplaySize()
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
            "LAND_MODE_REQ" -> {
                val requested = para?.optInt("Orientation", P.ORIENTATION_LANDSCAPE)
                    ?: P.ORIENTATION_LANDSCAPE

                // This bridge forwards an Android Auto landscape surface. Tell the
                // receiver to use the same coordinate space for reverse touch.
                negotiatedOrientation = P.ORIENTATION_LANDSCAPE
                send(P.landModeResponse(negotiatedOrientation))

                val phone = if (App.provide(context).settings.qdLinkActiveAreaMargins) {
                    width to height
                } else realDisplaySize()
                val mirror = fitMirrorSize(width, height, phone.first, phone.second)
                touchWidth = phone.first
                touchHeight = phone.second
                send(P.control("PHONE_INFO_CHANGE", P.phoneInfoChange(
                    phone.first, phone.second, mirror.first, mirror.second,
                    width, height
                ), 1))
                AppLog.i("QDLink: LAND_MODE_REQ orientation=$requested -> " +
                    "LAND_MODE_RSP orientation=$negotiatedOrientation + PHONE_INFO_CHANGE")
            }
            "VIDEO_ARGS" -> { width = para?.optInt("Width", width) ?: width; height = para?.optInt("Height", height) ?: height; fps = para?.optInt("FrameRate", 30) ?: 30; bitRate = para?.optInt("BitRate", bitRate) ?: bitRate; interval = para?.optInt("FrameInterval", 3) ?: 3 }
            "HEARTBEAT" -> send(P.control("HEARTBEAT", null, 1))
            "VIDEO_CTRL" -> {
                playing = para?.optInt("PlayStatus", 0) == 1
                AppLog.i("QDLink: head unit set playing=$playing; codec config known=${config != null}, key frame handler=${onKeyFrameRequest != null}")
                if (playing) {
                    configSent = false; resendConfig = true; onKeyFrameRequest?.invoke()
                    startPlaceholder()
                } else stopPlaceholder()
            }
            "KEY_FRAME_REQ" -> { resendConfig = true; onKeyFrameRequest?.invoke() }
            "DISCONNECT_REQ" -> send(P.control("DISCONNECT_RSP"))
        }
    }

    private var touchLogged = 0
    private fun parseTouch(b: ByteArray) {
        if (b.size < 15) return
        val flag = P.int(b, 0); val action = when (flag) { 0 -> 0; 2 -> 2; 1 -> 1; else -> return }
        if (touchLogged++ < 20) AppLog.i("QDLink rx: touch action=$action x=${P.float(b, 7)} y=${P.float(b, 11)} handler=${onTouch != null}")
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
            fps, bitRate, interval, negotiatedOrientation, P.ANGLE_LANDSCAPE
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
                writeStartedAt = android.os.SystemClock.elapsedRealtime()
                if (usbTransport) out?.write(usbAligned(packet, declared))
                else {
                    out?.write(packet, 0, declared)
                    out?.flush()
                }
                if (videoSent++ == 0) AppLog.i("QDLink: first video frame written ($kind, $declared bytes)")
            } catch (e: Exception) { logTxFailure(kind, e) }
            finally { writeStartedAt = 0L }
        }
        videoWireCount++
        if (kind != "P" || videoWireCount % fps.coerceAtLeast(1) == 0L) {
            AppLog.i("QDLink wire: $kind declared=$declared allocated=${packet.size} transmitted=$declared payload=${au.size}")
        }
    }
    private fun send(bytes: ByteArray) = synchronized(writeLock) {
        try {
            writeStartedAt = android.os.SystemClock.elapsedRealtime()
            if (usbTransport) out?.write(usbAligned(bytes, bytes.size))
            else { out?.write(bytes); out?.flush() }
            if (out != null) txFrames++
        } catch (e: Exception) { logTxFailure("control", e) }
        finally { writeStartedAt = 0L }
    }

    private fun logTxFailure(kind: String, e: Exception) {
        if (txFailures++ % 50 == 0) AppLog.w("QDLink tx: $kind write failed (failures=$txFailures): $e")
    }

    private fun hex(b: ByteArray, n: Int) = (0 until n).joinToString("") { "%02x".format(b[it]) }

    /** Zero-fill to the next 512-byte block; embedded frame lengths stay exact. */
    private fun usbAligned(bytes: ByteArray, length: Int): ByteArray {
        val aligned = (length + 511) and -512
        if (aligned == length && length == bytes.size) return bytes
        return ByteArray(aligned).also { System.arraycopy(bytes, 0, it, 0, length) }
    }

    private companion object {
        /** The head unit sends a heartbeat every three seconds; allow two to go missing. */
        const val USB_ALIVE_MS = 10_000L
        const val SOURCE_NONE = 0
        const val SOURCE_ANDROID_AUTO = 1
        const val SOURCE_PLACEHOLDER = 2
    }

    private object P {
        const val UDP_IN = 18463; const val UDP_OUT = 18464
        const val USB_MANUFACTURER = "Neusoft"; const val USB_MODEL = "QDriveLink"
        // Production h0.d metadata: orls=0 portrait, orls=1 landscape.
        const val ORIENTATION_PORTRAIT = 0
        const val ORIENTATION_LANDSCAPE = 1
        const val ANGLE_LANDSCAPE = 90
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
        fun phoneInfoChange(phoneW:Int,phoneH:Int,mirrorW:Int,mirrorH:Int,carW:Int,carH:Int)=JSONObject().put("PhoneWidth",phoneW).put("PhoneHeight",phoneH).put("MirrorWidth",mirrorW).put("MirrorHeight",mirrorH).put("PhoneWidthInApp",carW).put("PhoneHeightInApp",carH).put("MirrorWidthInApp",carW).put("MirrorHeightInApp",carH)
        fun landModeResponse(orientation:Int)=control("LAND_MODE_RSP",JSONObject().put("Orientation",orientation).put("Authority",1).put("StatusArg",1),1)
        fun video(au:ByteArray,w:Int,h:Int,fps:Int,br:Int,fi:Int,orientation:Int,angle:Int):ByteArray {
            require(angle == 0 || angle == 90 || angle == 180 || angle == 270) {
                "angle must be 0, 90, 180, or 270"
            }
            val d=ByteArray(32);short(32,d,0);d[2]=1;put(w,d,4);put(h,d,8)
            short(angle,d,12);d[14]=orientation.toByte();d[15]=3
            put(fps,d,16);put(br,d,20);put(fi,d,24);d[28]=2
            val a=ByteArray(16);ctrl.copyInto(a);put(48+au.size,a,4);short(32,a,8);a[10]=1;a[13]=2
            return a+d+au
        }
    }
}
