package com.remodroid.windows.rtc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.remodroid.shared.rtc.RtcConfig
import com.remodroid.shared.rtc.RtcEvent
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

data class RenderedFrame(val image: ImageBitmap, val width: Int, val height: Int, val serial: Long)

/**
 * Receives the remote screen and converts each decoded I420 frame to a BGRA Skia bitmap for Compose.
 * Three bitmaps rotate so the UI never draws a buffer that is being written.
 */
class DesktopViewerPeer(factory: PeerConnectionFactory, config: RtcConfig) : DesktopPeer(factory, config) {
    override val isSender = false

    private val _frame = MutableStateFlow<RenderedFrame?>(null)
    val frame: StateFlow<RenderedFrame?> = _frame.asStateFlow()

    @Volatile private var track: VideoTrack? = null
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var next = 0
    private var serial = 0L
    private var bytes = ByteArray(0)
    private var firstFrame = true

    private val sink = VideoTrackSink { frame -> render(frame) }

    override fun onRemoteTrack(transceiver: RTCRtpTransceiver) {
        val t = transceiver.receiver.track as? VideoTrack ?: return
        track = t
        t.addSink(sink)
    }

    private fun render(frame: VideoFrame) {
        if (closed.get()) return
        val i420 = frame.buffer.toI420()
        try {
            val w = i420.width
            val h = i420.height
            val size = w * h * 4
            if (bytes.size != size) bytes = ByteArray(size)
            // libyuv "ARGB" is B,G,R,A in memory, i.e. Skia BGRA_8888.
            VideoBufferConverter.convertFromI420(i420, bytes, FourCC.ARGB)
            val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            val bmp = bitmaps[next]?.takeIf { it.width == w && it.height == h } ?: Bitmap().also {
                bitmaps[next]?.close()
                it.allocPixels(info)
                bitmaps[next] = it
            }
            next = (next + 1) % bitmaps.size
            bmp.installPixels(info, bytes, w * 4)
            bmp.notifyPixelsChanged()
            _frame.value = RenderedFrame(bmp.asComposeImageBitmap(), w, h, ++serial)
            if (firstFrame) {
                firstFrame = false
                emit(RtcEvent.FirstFrameRendered)
            }
        } finally {
            i420.release()
        }
    }

    override fun close() {
        if (closed.get()) return
        track?.removeSink(sink)
        super.close()
        _frame.value = null
        bitmaps.forEachIndexed { i, b ->
            b?.close()
            bitmaps[i] = null
        }
    }
}
