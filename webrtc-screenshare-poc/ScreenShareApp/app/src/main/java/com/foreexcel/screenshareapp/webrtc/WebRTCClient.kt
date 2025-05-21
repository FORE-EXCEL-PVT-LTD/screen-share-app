package com.foreexcel.screenshareapp.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.webrtc.audio.JavaAudioDeviceModule

class WebRTCClient(
    private val context: Context,
    private val mediaCommunicationListener: RTCCommunicationListener
) {

    companion object {
        private const val TAG = "WebRTCClient"
        private const val LOCAL_TRACK_ID = "local_track"
        private const val LOCAL_STREAM_ID = "local_stream"
    }

    interface RTCCommunicationListener {
        fun onSessionDescriptionSend(sessionDescription: SessionDescription)
        fun onIceCandidateSend(iceCandidate: IceCandidate)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onScreenCaptureEnded()
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    // Add a flag to track screen capture state
    private var isCapturingScreen = false

    // WebRTC Components
    private val rootEglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localPeer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var screenCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    init {
        initializeWebRTC()
    }

    private fun initializeWebRTC() {
        // Initialize PeerConnectionFactory options
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)

        // Create PeerConnectionFactory
        val peerConnectionFactoryOptions = PeerConnectionFactory.Options()

        // Create an AudioDeviceModule for better audio handling
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(peerConnectionFactoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    fun initializePeerConnection() {
        if (peerConnectionFactory == null) {
            Log.e(TAG, "PeerConnectionFactory is not initialized")
            return
        }

        val rtcConfig = PeerConnection.RTCConfiguration(getIceServers()).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        // Initialize local peer connection
        localPeer = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    mediaCommunicationListener.onIceCandidateSend(iceCandidate)
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    mediaCommunicationListener.onConnectionStateChanged(newState)
                }

                // Implement other required observer methods
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}

                // Fixed the parameter names to avoid the conflict
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )
    }

    fun setupAudioSource() {
        if (peerConnectionFactory == null) {
            Log.e(TAG, "PeerConnectionFactory is not initialized")
            return
        }

        if (audioSource == null) {
            // Create audio constraints
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }

            // Create audio source
            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)

            // Create audio track
            audioTrack = peerConnectionFactory?.createAudioTrack("$LOCAL_TRACK_ID-audio", audioSource)

            // Add audio track to peer connection
            audioTrack?.let { track ->
                localPeer?.addTrack(track, listOf(LOCAL_STREAM_ID))
            }
        }
    }

    // Modified method that takes the resultCode and data Intent
    fun startScreenCapture(resultCode: Int, data: Intent, width: Int = 1280, height: Int = 720, fps: Int = 30) {
        if (peerConnectionFactory == null) {
            Log.e(TAG, "PeerConnectionFactory is not initialized")
            return
        }
        if (isCapturingScreen) {
            Log.d(TAG, "Screen capture already in progress, ignoring")
            return
        }

        try {
            // Create a callback for the MediaProjection
            val mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "Media projection stopped")
                }
            }

            // Create screen capturer using the permission data Intent and callback
            screenCapturer = ScreenCapturerAndroid(data, mediaProjectionCallback)

            // Create video source
            videoSource = peerConnectionFactory?.createVideoSource(true)

            // Initialize capturer
            val surfaceTextureHelper = SurfaceTextureHelper.create(
                "ScreenCapture",
                rootEglBase.eglBaseContext
            )

            screenCapturer?.initialize(
                surfaceTextureHelper,
                context,
                videoSource?.capturerObserver
            )

            // Create video track
            videoTrack = peerConnectionFactory?.createVideoTrack("$LOCAL_TRACK_ID-video", videoSource)

            // Add video track to peer connection
            videoTrack?.let { track ->
                localPeer?.addTrack(track, listOf(LOCAL_STREAM_ID))
            }

            // Start capturing
            screenCapturer?.startCapture(width, height, fps)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen capture: ${e.message}")
            e.printStackTrace()
            isCapturingScreen = false
        }
    }

    fun stopScreenCapture() {
        if (!isCapturingScreen) {
            Log.d(TAG, "No active screen capture to stop")
            return
        }

        try {
            Log.d(TAG, "Stopping screen capture")
            isCapturingScreen = false

            // Stop screen capture in a safe way
            try {
                screenCapturer?.stopCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping screen capture: ${e.message}")
            }

            // Clean up resources
            videoTrack?.let { track ->
                // Remove track from peer connection if needed
                val senders = localPeer?.senders?.toList() ?: emptyList()
                for (sender in senders) {
                    if (sender.track() == track) {
                        localPeer?.removeTrack(sender)
                    }
                }
            }

            videoTrack?.dispose()
            videoTrack = null

            videoSource?.dispose()
            videoSource = null

            screenCapturer?.dispose()
            screenCapturer = null

            Log.d(TAG, "Screen capture stopped and resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error in stopScreenCapture: ${e.message}")
            e.printStackTrace()
        }
    }

    fun createOffer() {
        // Only create an offer if we're not already in a connection process
        if (isCreatingOffer) {
            Log.d(TAG, "Offer creation already in progress")
            return
        }

        isCreatingOffer = true

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        localPeer?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                localPeer?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local offer set successfully")
                        isCreatingOffer = false
                        mediaCommunicationListener.onSessionDescriptionSend(sessionDescription)
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {
                        Log.e(TAG, "Create local description failed: $p0")
                        isCreatingOffer = false
                    }
                    override fun onSetFailure(p0: String?) {
                        Log.e(TAG, "Failed to set local description: $p0")
                        isCreatingOffer = false
                    }
                }, sessionDescription)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
                isCreatingOffer = false
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private var isCreatingOffer = false

    fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        localPeer?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                localPeer?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local answer set successfully")
                        mediaCommunicationListener.onSessionDescriptionSend(sessionDescription)
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {
                        Log.e(TAG, "Failed to set local description: $p0")
                    }
                }, sessionDescription)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create answer failed: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(sessionDescription: SessionDescription) {
        localPeer?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully")
                if (sessionDescription.type == SessionDescription.Type.OFFER) {
                    createAnswer()
                }
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                Log.e(TAG, "Failed to set remote description: $p0")
            }
        }, sessionDescription)
    }

    fun addIceCandidate(iceCandidate: IceCandidate) {
        localPeer?.addIceCandidate(iceCandidate)
    }

    private fun getIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    fun dispose() {
        // Stop and dispose screen capture
        try {
            screenCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen capture: ${e.message}")
        }

        screenCapturer?.dispose()
        screenCapturer = null

        videoTrack?.dispose()
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        // Dispose audio resources
        audioTrack?.dispose()
        audioTrack = null

        audioSource?.dispose()
        audioSource = null

        // Close and dispose peer connection
        localPeer?.close()
        localPeer = null

        // Dispose factory
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        // Release EGL context
        rootEglBase.release()
    }
}