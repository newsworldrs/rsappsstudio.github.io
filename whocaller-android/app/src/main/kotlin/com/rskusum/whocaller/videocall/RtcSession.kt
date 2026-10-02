package com.rskusum.whocaller.videocall

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WHOCALLER VIDEO (experimental): one WebRTC call — camera, microphone and a peer connection.
 * Media goes phone-to-phone (or through the TURN relay when networks block that).
 */
class RtcSession(context: Context, iceServers: List<PeerConnection.IceServer>, private val listener: Listener) {

    interface Listener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onRemoteVideo(track: VideoTrack)
        fun onState(state: PeerConnection.PeerConnectionState)
    }

    private val app = context.applicationContext
    val egl: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    var localVideo: VideoTrack? = null
        private set
    private var localAudio: AudioTrack? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteSet = false
    private val audio = app.getSystemService(AudioManager::class.java)
    private val previousAudioMode = audio?.mode ?: AudioManager.MODE_NORMAL
    var frontCamera = true
        private set

    init {
        initialize(app)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(app).createAudioDeviceModule())
            .createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = requireNotNull(factory.createPeerConnection(config, observer)) { "Couldn't create the video connection" }
    }

    /** Starts camera and microphone and adds them to the call. */
    fun startLocalMedia() {
        audio?.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audio?.isSpeakerphoneOn = true

        val enumerator = Camera2Enumerator(app)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull()
        frontCamera = name != null && enumerator.isFrontFacing(name)
        val cam = name?.let { enumerator.createCapturer(it, null) }
        if (cam != null) {
            val helper = SurfaceTextureHelper.create("WhoCallerCapture", egl.eglBaseContext)
            val source = factory.createVideoSource(false)
            cam.initialize(helper, app, source.capturerObserver)
            cam.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
            capturer = cam
            textureHelper = helper
            videoSource = source
            localVideo = factory.createVideoTrack("video0", source).also { peer.addTrack(it, listOf(STREAM)) }
        }
        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudio = factory.createAudioTrack("audio0", audioSource).also { peer.addTrack(it, listOf(STREAM)) }
    }

    fun setMicOn(on: Boolean) {
        localAudio?.setEnabled(on)
    }

    fun setCameraOn(on: Boolean) {
        localVideo?.setEnabled(on)
        runCatching { if (on) capturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS) else capturer?.stopCapture() }
    }

    fun switchCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                frontCamera = isFront
            }
            override fun onCameraSwitchError(error: String?) = Unit
        })
    }

    fun setSpeaker(on: Boolean) {
        @Suppress("DEPRECATION")
        audio?.isSpeakerphoneOn = on
    }

    suspend fun createOffer(): SessionDescription {
        val offer = create { peer.createOffer(it, mediaConstraints()) }
        setLocal(offer)
        return offer
    }

    suspend fun createAnswer(): SessionDescription {
        val answer = create { peer.createAnswer(it, mediaConstraints()) }
        setLocal(answer)
        return answer
    }

    suspend fun setRemote(description: SessionDescription) {
        set { peer.setRemoteDescription(it, description) }
        synchronized(pendingCandidates) {
            remoteSet = true
            pendingCandidates.forEach { peer.addIceCandidate(it) }
            pendingCandidates.clear()
        }
    }

    /** Remote network paths; kept until the remote description is set. */
    fun addRemoteCandidate(candidate: IceCandidate) {
        synchronized(pendingCandidates) {
            if (remoteSet) peer.addIceCandidate(candidate) else pendingCandidates += candidate
        }
    }

    fun close() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { localVideo?.dispose() }
        runCatching { localAudio?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { textureHelper?.dispose() }
        runCatching { peer.close() }
        runCatching { peer.dispose() }
        runCatching { factory.dispose() }
        runCatching { egl.release() }
        audio?.mode = previousAudioMode
        @Suppress("DEPRECATION")
        audio?.isSpeakerphoneOn = false
    }

    private suspend fun setLocal(description: SessionDescription) = set { peer.setLocalDescription(it, description) }

    private fun mediaConstraints() = MediaConstraints().apply {
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
    }

    private suspend fun create(start: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        start(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)
            override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException(error ?: "create failed"))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        })
    }

    private suspend fun set(start: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        start(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException(error ?: "set failed"))
        })
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = listener.onIceCandidate(candidate)
        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let(listener::onRemoteVideo)
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = listener.onState(newState)
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    companion object {
        private const val STREAM = "whocaller"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
        @Volatile private var initialized = false

        private fun initialize(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                initialized = true
            }
        }

        fun iceServer(urls: List<String>, username: String? = null, credential: String? = null): PeerConnection.IceServer =
            PeerConnection.IceServer.builder(urls).apply {
                if (username != null) setUsername(username)
                if (credential != null) setPassword(credential)
            }.createIceServer()
    }
}
