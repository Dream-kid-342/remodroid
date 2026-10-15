package com.remodroid.android.rtc

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import com.remodroid.shared.control.CONTROL_CHANNEL_LABEL
import com.remodroid.shared.control.CoordinateMapper
import com.remodroid.shared.control.ScreenInfo
import com.remodroid.shared.rtc.HostRtcPeer
import com.remodroid.shared.rtc.RtcConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** Physical size and rotation of the default display. */
fun displayInfo(context: Context): ScreenInfo {
    val dm = context.getSystemService(DisplayManager::class.java)
    val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    display.getRealMetrics(metrics)
    val rotation = when (display.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    return ScreenInfo(metrics.widthPixels, metrics.heightPixels, rotation)
}

/**
 * Sends the device screen captured through MediaProjection. The capture is stopped and every surface,
 * virtual display, encoder and the peer connection are released in [close].
 */
class AndroidHostPeer(
    private val context: Context,
    factory: PeerConnectionFactory,
    eglBase: EglBase,
    private val config: RtcConfig,
    projectionData: Intent,
    private val onCaptureStopped: () -> Unit,
) : AndroidPeer(factory, config), HostRtcPeer {
    override val isSender = true

    private val _screen = MutableStateFlow<ScreenInfo?>(displayInfo(context))
    override val screen: StateFlow<ScreenInfo?> = _screen.asStateFlow()

    private val surfaceTextureHelper = SurfaceTextureHelper.create("RemoDroidCapture", eglBase.eglBaseContext)
    private val videoSource: VideoSource = factory.createVideoSource(/* isScreencast = */ true)
    private val capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
        // The user stopped sharing from the system UI, or another app took over the projection.
        override fun onStop() {
            if (!closed.get()) mainHandler.post(onCaptureStopped)
        }
    })
    private lateinit var track: VideoTrack
    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var captureSize: Pair<Int, Int>

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || closed.get()) return
            val info = displayInfo(context)
            val size = CoordinateMapper.captureSize(info.width, info.height, config.quality.maxLongEdge)
            if (size != captureSize) {
                // Orientation change: recreate the virtual display with swapped dimensions.
                captureSize = size
                capturer.changeCaptureFormat(size.first, size.second, config.quality.maxFps)
            }
            _screen.value = info
        }
    }

    init {
        val info = _screen.value!!
        captureSize = CoordinateMapper.captureSize(info.width, info.height, config.quality.maxLongEdge)
        try {
            capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            // Throws SecurityException if the consent token was already used or the FGS is not running.
            capturer.startCapture(captureSize.first, captureSize.second, config.quality.maxFps)
        } catch (e: RuntimeException) {
            super.close()
            capturer.dispose()
            videoSource.dispose()
            surfaceTextureHelper.dispose()
            throw e
        }

        track = factory.createVideoTrack("remodroid-screen", videoSource)
        val sender = pc.addTrack(track, listOf("remodroid"))
        sender.parameters = sender.parameters.apply {
            degradationPreference = RtpParameters.DegradationPreference.BALANCED
            encodings.forEach {
                it.maxBitrateBps = config.quality.maxBitrateKbps * 1000
                it.maxFramerate = config.quality.maxFps
            }
        }
        attachDataChannel(pc.createDataChannel(CONTROL_CHANNEL_LABEL, DataChannel.Init().apply { ordered = true }))
        displayManager.registerDisplayListener(displayListener, mainHandler)
    }

    /** A disabled track sends black frames: the connection stays up but nothing is visible. */
    override fun setVideoEnabled(enabled: Boolean) {
        if (!closed.get() && ::track.isInitialized) track.setEnabled(enabled)
    }

    override fun close() {
        if (closed.get()) return
        displayManager.unregisterDisplayListener(displayListener)
        try {
            capturer.stopCapture()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        super.close()
        capturer.dispose()
        if (::track.isInitialized) track.dispose()
        videoSource.dispose()
        surfaceTextureHelper.dispose()
    }
}
