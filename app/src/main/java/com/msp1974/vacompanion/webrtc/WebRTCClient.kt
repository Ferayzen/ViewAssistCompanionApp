package com.msp1974.vacompanion.webrtc

import android.content.Context
import com.msp1974.vacompanion.utils.Logger
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

interface WebRTCListener {
    fun onLocalSdp(type: String, sdp: String)
    fun onIceCandidate(candidate: IceCandidate)
    fun onRemoteStreamAvailable()
}

class WebRTCClient(private val ctx: Context, private val listener: WebRTCListener) {
    private val log = Logger()

    companion object {
        @Volatile
        private var nativeInitialized = false

        @Synchronized
        private fun initializeNative(ctx: Context) {
            if (!nativeInitialized) {
                val initOptions = PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(initOptions)
                nativeInitialized = true
            }
        }
    }

    // EGL context — only created when video is enabled (API >= 28)
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    // Guards to prevent double-init
    private var localCapturing = false
    private var localRendererAttached = false
    private var remoteRendererInitialized = false
    private var disposed = false

    /** Expose the shared EGL context so renderers can be pre-initialised. */
    fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext

    fun init() {
        // Native init is process-global and must only happen once.
        // Calling initialize() after a previous factory.dispose() can corrupt
        // the internal audio tracer on some older devices.
        initializeNative(ctx)

        eglBase = EglBase.create()
        log.d("WebRTC init — EGL + HW codecs")

        // Create an explicit AudioDeviceModule so we have full control over its
        // lifecycle.  Without this the factory creates a default one internally
        // whose release is implicit and can silently fail on some devices.
        audioDeviceModule = JavaAudioDeviceModule.builder(ctx)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(audioDeviceModule)
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
                        // Ensure sink attachment happens on main thread to avoid renderer threading issues
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                remoteRenderer?.let { track.addSink(it) }
                            } catch (e: Exception) {
                                log.e("Error attaching remote sink on main thread: $e")
                            }
                        }
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
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                remoteRenderer?.let { track.addSink(it) }
                            } catch (e: Exception) {
                                log.e("Error attaching remote sink on main thread: $e")
                            }
                        }
                        listener.onRemoteStreamAvailable()
                    }
                } catch (e: Exception) {
                    log.e("Error onTrack: $e")
                }
            }
        })

        // ---- Audio ----
        val audioConstraints = MediaConstraints()
        audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack)
        log.d("Audio track created, enabled=${localAudioTrack?.enabled()}, state=${localAudioTrack?.state()}")

        // ---- Video ----
        try {
            videoCapturer = createCameraCapturer()
            surfaceTextureHelper = SurfaceTextureHelper.create("WebRTC-CaptureThread", eglBase!!.eglBaseContext)
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
        val useCamera2 = try {
            Camera2Enumerator.isSupported(ctx)
        } catch (e: Exception) {
            log.e("Camera2.isSupported check failed: $e")
            false
        }

        val enumerator: CameraEnumerator = if (useCamera2) {
            try {
                Camera2Enumerator(ctx)
            } catch (e: Exception) {
                log.e("Failed to create Camera2Enumerator: $e — falling back to Camera1Enumerator")
                Camera1Enumerator(true)
            }
        } else {
            Camera1Enumerator(true)
        }

        val deviceNames = try { enumerator.deviceNames } catch (e: Exception) {
            log.e("Failed to enumerate camera devices: $e")
            arrayOf<String>()
        }

        var chosenDevice: String? = null
        for (name in deviceNames) {
            try {
                if (enumerator.isFrontFacing(name)) {
                    chosenDevice = name
                    break
                }
            } catch (e: Exception) {
                // ignore per-device errors
            }
        }
        if (chosenDevice == null && deviceNames.isNotEmpty()) chosenDevice = deviceNames[0]
        log.d("Camera device chosen: $chosenDevice (using ${if (useCamera2) "Camera2" else "Camera1"})")
        return if (chosenDevice != null) {
            try {
                enumerator.createCapturer(chosenDevice, null)
            } catch (e: Exception) {
                log.e("Failed to create capturer for $chosenDevice: $e")
                null
            }
        } else null
    }

    /**
     * Initialise the local renderer and start the camera.
     * No-op on audio-only devices.
     * Safe to call multiple times — capture and renderer init are guarded.
     */
    fun startLocalVideo(localRenderer: SurfaceViewRenderer) {
        // Initialise the renderer only once
        if (!localRendererAttached) {
            try {
                localRenderer.init(eglBase!!.eglBaseContext, null)
                localRenderer.setMirror(true)
                try { localRenderer.setEnableHardwareScaler(false) } catch (_: Exception) {}
                try { localRenderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT) } catch (_: Exception) {}
                localRendererAttached = true
                log.d("Local renderer initialised")
            } catch (e: Exception) {
                log.e("Error initialising local renderer (may already be initialised): $e")
                localRendererAttached = true  // assume already initialised
            }
        }

        // Attach the renderer as a sink (on main thread)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                localVideoTrack?.addSink(localRenderer)
            } catch (e: Exception) {
                log.e("Error attaching local sink on main thread: $e")
            }
        }

        // Start camera capture if not already started. Try a couple of fallbacks for older devices.
        if (!localCapturing) {
            try {
                videoCapturer?.startCapture(640, 480, 30)
                localCapturing = true
                log.d("Camera capture started (640x480@30)")
            } catch (e: Exception) {
                log.e("Error starting camera capture at 640x480: $e — trying 320x240@15")
                try {
                    videoCapturer?.startCapture(320, 240, 15)
                    localCapturing = true
                    log.d("Camera capture started (320x240@15)")
                } catch (e2: Exception) {
                    log.e("Error starting camera capture at 320x240: $e2")
                    // Notify user-friendly message via Logger only (UI layer can react to logs)
                }
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
        // Note: capturer and surfaceTextureHelper are disposed in dispose()
    }

    /**
     * Attach a renderer for the remote video stream.
     * No-op on audio-only devices.
     * Safe to call multiple times.
     */
    fun setRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        if (!remoteRendererInitialized) {
            try {
                remoteRenderer?.init(eglBase!!.eglBaseContext, null)
                try { remoteRenderer?.setEnableHardwareScaler(false) } catch (_: Exception) {}
                try { remoteRenderer?.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT) } catch (_: Exception) {}
                try { remoteRenderer?.setMirror(false) } catch (_: Exception) {}
                remoteRendererInitialized = true
                log.d("Remote renderer initialised")
            } catch (e: Exception) {
                log.e("Error initialising remote renderer (may already be initialised): $e")
                remoteRendererInitialized = true
            }
        }
        // Attach remote video on main thread to avoid renderer threading issues
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                remoteVideoTrack?.let {
                    it.addSink(remoteRenderer)
                    log.d("Remote video track attached to renderer")
                }
            } catch (e: Exception) {
                log.e("Error attaching remote video track on main thread: $e")
            }
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

    /**
     * Release SurfaceViewRenderers. Must be called BEFORE [dispose] because
     * renderers depend on the EGL context that dispose() releases.
     */
    fun releaseRenderers(localRenderer: SurfaceViewRenderer?, remoteRenderer: SurfaceViewRenderer?) {
        log.d("Releasing renderers")
        // Remove sinks first so tracks don't write to dead renderers
        try { localVideoTrack?.removeSink(localRenderer) } catch (_: Exception) {}
        try { remoteVideoTrack?.removeSink(remoteRenderer) } catch (_: Exception) {}
        // Release renderer EGL surfaces
        try { localRenderer?.release() } catch (_: Exception) {}
        try { remoteRenderer?.release() } catch (_: Exception) {}
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        log.d("Disposing WebRTCClient")
        try {
            // 1. Stop camera capture (but don't dispose capturer yet)
            stopLocalVideo()

            // 2. Close peer connection FIRST — detaches tracks from transceivers internally.
            //    This must happen before disposing tracks/sources or native resources
            //    may not be properly released on older devices.
            try { peerConnection?.close() } catch (_: Exception) {}
            peerConnection = null

            // 3. Disable and dispose tracks (releases native mic/camera handles)
            try { localAudioTrack?.setEnabled(false) } catch (_: Exception) {}
            try { localVideoTrack?.setEnabled(false) } catch (_: Exception) {}
            try { localAudioTrack?.dispose() } catch (_: Exception) {}
            try { localVideoTrack?.dispose() } catch (_: Exception) {}
            localAudioTrack = null
            localVideoTrack = null
            remoteVideoTrack = null

            // 4. Dispose sources (audio source releases the internal AudioRecord)
            try { audioSource?.dispose() } catch (_: Exception) {}
            try { videoSource?.dispose() } catch (_: Exception) {}
            audioSource = null
            videoSource = null

            // 5. Dispose capturer and surface texture helper
            try { videoCapturer?.dispose() } catch (_: Exception) {}
            try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
            videoCapturer = null
            surfaceTextureHelper = null

            // 6. Release the explicit audio device module (releases native AudioRecord/AudioTrack)
            try { audioDeviceModule?.release() } catch (_: Exception) {}
            audioDeviceModule = null

            // 7. Dispose factory
            try { peerConnectionFactory?.dispose() } catch (_: Exception) {}
            peerConnectionFactory = null

            // 8. Release EGL context last (renderers must be released before this!)
            try { eglBase?.release() } catch (_: Exception) {}
            eglBase = null

            remoteRenderer = null
            log.d("WebRTCClient disposed successfully")
        } catch (e: Exception) {
            log.e("Error disposing WebRTCClient: $e")
        }
    }
}
