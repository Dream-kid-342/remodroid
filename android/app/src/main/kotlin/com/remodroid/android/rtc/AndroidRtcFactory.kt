package com.remodroid.android.rtc

import android.content.Context
import com.remodroid.android.service.ProjectionConsent
import com.remodroid.android.service.ScreenShareService
import com.remodroid.shared.rtc.HostRtcPeer
import com.remodroid.shared.rtc.RtcConfig
import com.remodroid.shared.rtc.RtcFactory
import com.remodroid.shared.rtc.RtcPeer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

class AndroidRtcFactory(
    private val context: Context,
    private val onCaptureStopped: () -> Unit,
) : RtcFactory {
    val eglBase: EglBase by lazy { EglBase.create() }

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
        )
        // RemoDroid sends no audio; the module is required by the factory but never starts recording.
        val audio = JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { audio.release() }
    }

    override suspend fun createHostPeer(config: RtcConfig): HostRtcPeer {
        val consent = ProjectionConsent.take()
            ?: throw IllegalStateException("Screen capture permission was not granted")
        // Android 14+ requires the mediaProjection foreground service to be running before capture starts.
        withTimeoutOrNull(5_000) { ScreenShareService.running.first { it } }
            ?: throw IllegalStateException("The screen sharing service did not start")
        return AndroidHostPeer(context.applicationContext, factory, eglBase, config, consent, onCaptureStopped)
    }

    override suspend fun createViewerPeer(config: RtcConfig): RtcPeer = AndroidViewerPeer(factory, config)
}
