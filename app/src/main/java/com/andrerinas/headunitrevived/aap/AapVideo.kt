package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.qdlink.QdLinkBridge
import java.nio.ByteBuffer

internal class AapVideo(private val videoDecoder: VideoDecoder, private val settings: Settings,
                        private val qdLink: QdLinkBridge, private val onFrameCorrupted: () -> Unit) {

    private val messageBuffer = ByteBuffer.allocate(
        if (settings.videoCodec == VideoDecoder.CodecType.H265.mimeType) {
            Messages.DEF_BUFFER_LENGTH * 64 // ~8MB for H.265 support
        } else {
            Messages.DEF_BUFFER_LENGTH * 16 // ~2MB for H.264 legacy support
        }
    )
    private var legacyAssembledBuffer: ByteArray? = null
    private var isFrameCorrupt = false
    private var lastKeyframeRequestMs = 0L
    private var qdLinkStartLogged = false

    private fun markCorruptAndRequestRecovery() {
        if (!isFrameCorrupt) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastKeyframeRequestMs > 1000) {
                lastKeyframeRequestMs = now
                AppLog.w("AapVideo: Frame corrupted, requesting keyframe to recover stream")
                onFrameCorrupted()
            }
        }
        isFrameCorrupt = true
    }

    private fun findStartCode(buf: ByteArray, offset: Int): Int {
        if (offset + 3 > buf.size) return -1
        if (buf[offset].toInt() == 0 && buf[offset + 1].toInt() == 0) {
            if (buf[offset + 2].toInt() == 1) return 3 // 3-byte start code
            if (offset + 4 <= buf.size && buf[offset + 2].toInt() == 0 && buf[offset + 3].toInt() == 1) return 4 // 4-byte start code
        }
        return -1
    }

    private fun output(buf: ByteArray, offset: Int, size: Int) {
        val mode = settings.videoOutputMode
        if (mode != Settings.VideoOutputMode.LOCAL) {
            // Do not make QDLink-only output depend on AapService having been
            // recreated after the preference changed. BOTH commonly has a local
            // Activity/surface lifecycle that masks this race; QDLINK has no
            // such local lifecycle. start() is idempotent and guarantees the
            // discovery/mirror endpoint exists before access units are handed
            // to it.
            if (!qdLink.isRunning) {
                qdLink.start()
                if (!qdLinkStartLogged) {
                    qdLinkStartLogged = true
                    AppLog.i("AapVideo: started QDLink bridge from video forwarding path")
                }
            }
            var p = offset
            var config = false
            var key = false
            val end = offset + size
            while (p + 4 < end) {
                val sc = findStartCode(buf, p)
                if (sc > 0) {
                    when (buf[p + sc].toInt() and 0x1f) {
                        7, 8 -> config = true
                        5 -> key = true
                    }
                    p += sc
                } else p++
            }
            qdLink.sendAccessUnit(buf, offset, size, config, key)
        }
        if (mode != Settings.VideoOutputMode.QDLINK) {
            videoDecoder.decode(buf, offset, size, settings.forceSoftwareDecoding, settings.videoCodec)
        }
    }

    fun process(message: AapMessage): Boolean {

        val flags = message.flags.toInt()
        val buf = message.data
        val len = message.size

        when (flags) {
            11 -> {
                // Single fragment frame - corruption only affects this frame
                isFrameCorrupt = false
                messageBuffer.clear()

                // Timestamp Indication (Offset 10)
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    output(buf, 10, len - 10)
                    return true
                }

                // Media Indication or Config (Offset 2)
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    output(buf, 2, len - 2)
                    return true
                }
                AppLog.w("AapVideo: Dropped Flag 11 packet. len=$len")
            }
            9 -> {
                // First fragment - reset corruption state for the new frame
                isFrameCorrupt = false
                messageBuffer.clear()

                // Timestamp Indication (Offset 10)
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    messageBuffer.put(message.data, 10, message.size - 10)
                    return true
                }
                // Media Indication (Offset 2)
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    messageBuffer.put(message.data, 2, message.size - 2)
                    return true
                }
            }
            8 -> {
                if (isFrameCorrupt) return true // Skip fragments of an already corrupt frame

                // Middle fragment - append to buffer with overflow detection
                if (messageBuffer.remaining() >= message.size) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AppLog.e("AapVideo: Fragment overflow (Flag 8)! Size ${message.size} exceeds remaining ${messageBuffer.remaining()}. Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                }
                return true
            }
            10 -> {
                if (isFrameCorrupt) return true // Skip fragments of an already corrupt frame

                // Last fragment - append, assemble, and decode
                if (messageBuffer.remaining() >= message.size) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AppLog.e("AapVideo: Final fragment overflow (Flag 10)! Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                    return true
                }

                messageBuffer.flip()
                val assembledSize = messageBuffer.limit()

                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                    if (legacyAssembledBuffer == null || legacyAssembledBuffer!!.size < assembledSize) {
                        legacyAssembledBuffer = ByteArray(assembledSize + 1024)
                    }
                    messageBuffer.get(legacyAssembledBuffer!!, 0, assembledSize)
                    output(legacyAssembledBuffer!!, 0, assembledSize)
                } else {
                    output(messageBuffer.array(), 0, assembledSize)
                }

                messageBuffer.clear()
                return true
            }
        }

        return false
    }

    fun release() {
        // Kept for AapTransport lifecycle compatibility. Decoding is synchronous here.
    }
}
