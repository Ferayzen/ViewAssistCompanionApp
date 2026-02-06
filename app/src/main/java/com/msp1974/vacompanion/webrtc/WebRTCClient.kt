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
    private var remoteRenderer: SurfaceViewRenderer? = null

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

    fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
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
                        remoteVideoTrack = track
                        remoteRenderer?.let { track.addSink(it) }
                        listener.onRemoteStreamAvailable()
                    }
                } catch (e: Exception) {
                    log.e("Error onTrack: $e")
                }
            }
        })

        // Create audio source
        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("ARDAMSa0", audioSource)

        // Add audio track to PeerConnection
        val audioSender = peerConnection?.addTrack(localAudioTrack)
    }

    fun startLocalVideo(localRenderer: SurfaceViewRenderer) {
        if (peerConnectionFactory == null) init()
        if (peerConnection == null) createPeerConnection()

        // Create video capturer (Camera2 if possible)
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
        videoCapturer = enumerator.createCapturer(chosenDevice, null)

        surfaceTextureHelper = SurfaceTextureHelper.create(Thread.currentThread().name, eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer?.initialize(surfaceTextureHelper, ctx, videoSource?.capturerObserver)
        videoCapturer?.startCapture(640, 480, 30)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
        // setup renderer
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(true)
        localVideoTrack?.addSink(localRenderer)

        // add local track to peer connection
        peerConnection?.addTrack(localVideoTrack)
    }

    fun stopLocalVideo() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {}
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        localVideoTrack = null
    }

    fun setRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        remoteRenderer?.init(eglBase.eglBaseContext, null)
        remoteVideoTrack?.let { it.addSink(remoteRenderer) }
    }

    fun createOffer() {
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        listener.onLocalSdp(sessionDescription.type.canonicalForm(), sessionDescription.description)
                    }
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sessionDescription)
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateFailure(p0: String?) {}
        }, constraints)
    }

    fun createAnswer() {
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        listener.onLocalSdp(sessionDescription.type.canonicalForm(), sessionDescription.description)
                    }
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sessionDescription)
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
            override fun onCreateFailure(p0: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(type: String, sdp: String) {
        val sd = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
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
            peerConnectionFactory?.dispose()
            eglBase.release()
        } catch (e: Exception) {
            log.e("Error disposing WebRTCClient: $e")
        }
    }
}
