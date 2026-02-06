package com.msp1974.vacompanion.webrtc

import android.content.Context
import com.msp1974.vacompanion.utils.Logger
import org.webrtc.*

interface WebRTCListener {
    fun onLocalSdp(type: String, sdp: String)
    fun onIceCandidate(candidate: IceCandidate)
    fun onRemoteStreamAvailable()
}

class WebRTCClient(private val ctx: Context, private val listener: WebRTCListener) {
    private val log = Logger()
    private var eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    // Guards to prevent double-init
    private var localCapturing = false
    private var localRendererAttached = false
    private var remoteRendererInitialized = false

    /** Expose the shared EGL context so renderers can be pre-initialised. */
    fun getEglContext(): EglBase.Context = eglBase.eglBaseContext

    fun init() {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(ctx)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    /**
     * Creates the PeerConnection **and** adds both audio AND video tracks
     * so the SDP always includes video regardless of whether a renderer
     * is attached yet.  Camera capture is NOT started here — call
     * [startLocalVideo] once a SurfaceViewRenderer is available.
     */
    fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                log.d("PC signalingState -> $newState")
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                log.d("PC iceConnectionState -> $newState")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                try {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        log.d("onAddTrack: remote video track received")
                        remoteVideoTrack = track
                        remoteRenderer?.let { track.addSink(it) }
                        listener.onRemoteStreamAvailable()
                    }
                } catch (e: Exception) {
                    log.e("Error onAddTrack: $e")
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                try {
                    val receiver = transceiver?.receiver
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        log.d("onTrack: remote video track received")
                        remoteVideoTrack = track
                        remoteRenderer?.let { track.addSink(it) }
                        listener.onRemoteStreamAvailable()
                    }
                } catch (e: Exception) {
                    log.e("Error onTrack: $e")
                }
            }
        })

        // ---- Audio ----
        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)
        peerConnection?.addTrack(localAudioTrack)

        // ---- Video (source + track) ----
        // Create the capturer, source, and track NOW so they are part of the
        // PeerConnection before any offer/answer is generated.  The actual
        // camera capture is deferred until startLocalVideo().
        try {
            videoCapturer = createCameraCapturer()
            surfaceTextureHelper = SurfaceTextureHelper.create("WebRTC-CaptureThread", eglBase.eglBaseContext)
            videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)
            videoCapturer?.initialize(surfaceTextureHelper, ctx, videoSource?.capturerObserver)

            localVideoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
            localVideoTrack?.setEnabled(true)
            peerConnection?.addTrack(localVideoTrack)
            log.d("Video track created and added to PeerConnection")
        } catch (e: Exception) {
            log.e("Error creating video track: $e")
        }
    }

    /** Enumerate cameras and prefer front-facing. */
    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(ctx)
        val deviceNames = enumerator.deviceNames
        var chosenDevice: String? = null
        for (name in deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                chosenDevice = name
                break
            }
        }
        if (chosenDevice == null && deviceNames.isNotEmpty()) chosenDevice = deviceNames[0]
        log.d("Camera device chosen: $chosenDevice")
        return if (chosenDevice != null) enumerator.createCapturer(chosenDevice, null) else null
    }

    /**
     * Initialise the local renderer and start the camera.
     * Safe to call multiple times — capture and renderer init are guarded.
     */
    fun startLocalVideo(localRenderer: SurfaceViewRenderer) {
        // Initialise the renderer only once
        if (!localRendererAttached) {
            try {
                localRenderer.init(eglBase.eglBaseContext, null)
                localRenderer.setMirror(true)
                localRendererAttached = true
                log.d("Local renderer initialised")
            } catch (e: Exception) {
                log.e("Error initialising local renderer (may already be initialised): $e")
                localRendererAttached = true  // assume already initialised
            }
        }

        // Attach the renderer as a sink
        localVideoTrack?.addSink(localRenderer)

        // Start camera capture if not already started
        if (!localCapturing) {
            try {
                videoCapturer?.startCapture(640, 480, 30)
                localCapturing = true
                log.d("Camera capture started (640x480@30)")
            } catch (e: Exception) {
                log.e("Error starting camera capture: $e")
            }
        }
    }

    fun stopLocalVideo() {
        try {
            if (localCapturing) {
                videoCapturer?.stopCapture()
                localCapturing = false
            }
        } catch (e: Exception) {
            log.e("Error stopping capture: $e")
        }
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
    }

    /**
     * Attach a renderer for the remote video stream.
     * Safe to call multiple times.
     */
    fun setRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        if (!remoteRendererInitialized) {
            try {
                remoteRenderer?.init(eglBase.eglBaseContext, null)
                remoteRendererInitialized = true
                log.d("Remote renderer initialised")
            } catch (e: Exception) {
                log.e("Error initialising remote renderer (may already be initialised): $e")
                remoteRendererInitialized = true
            }
        }
        remoteVideoTrack?.let {
            it.addSink(remoteRenderer)
            log.d("Remote video track attached to renderer")
        }
    }

    fun createOffer() {
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                log.d("Offer SDP created, setting local description")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        log.d("Local description set (offer)")
                        listener.onLocalSdp(sessionDescription.type.canonicalForm(), sessionDescription.description)
                    }
                    override fun onSetFailure(p0: String?) { log.e("setLocalDesc failed: $p0") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sessionDescription)
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateFailure(p0: String?) { log.e("createOffer failed: $p0") }
        }, constraints)
    }

    fun createAnswer() {
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                log.d("Answer SDP created, setting local description")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        log.d("Local description set (answer)")
                        listener.onLocalSdp(sessionDescription.type.canonicalForm(), sessionDescription.description)
                    }
                    override fun onSetFailure(p0: String?) { log.e("setLocalDesc(answer) failed: $p0") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sessionDescription)
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateFailure(p0: String?) { log.e("createAnswer failed: $p0") }
        }, constraints)
    }

    fun setRemoteDescription(type: String, sdp: String) {
        log.d("Setting remote description ($type)")
        val sd = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { log.d("Remote description set successfully ($type)") }
            override fun onSetFailure(p0: String?) { log.e("setRemoteDescription($type) failed: $p0") }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sd)
    }

    fun addRemoteIce(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun dispose() {
        try {
            stopLocalVideo()
            peerConnection?.close()
            peerConnection = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            eglBase.release()
        } catch (e: Exception) {
            log.e("Error disposing WebRTCClient: $e")
        }
    }
}
