package com.msp1974.vacompanion.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import org.webrtc.SurfaceViewRenderer
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.AuthUtils
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.webrtc.HASignalingClient
import com.msp1974.vacompanion.webrtc.HASignalingListener
import com.msp1974.vacompanion.webrtc.WebRTCClient
import com.msp1974.vacompanion.webrtc.WebRTCListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.json.JSONObject

class VideoCallActivity : ComponentActivity() {
    private val log = Logger()

    // Listen for "callEnded" event to finish this activity (remote side hang-up)
    private val callEndedListener = object : EventListener {
        override fun onEventTriggered(event: Event) {
            if (event.eventName == "callEnded") {
                log.d("Received callEnded event — finishing VideoCallActivity")
                val cfg = APPConfig.getInstance(this@VideoCallActivity)
                cfg.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", true))
                cfg.eventBroadcaster.notifyEvent(Event("resumeAudioInput", "", true))
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = APPConfig.getInstance(this)
        config.eventBroadcaster.addListener(callEndedListener)

        val initialTarget = extractTargetFromIntent(intent)

        setContent {
            // Black background — no visible bars
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                VideoCallScreen(
                    onBack = { target -> endCallAndFinish(config, target) },
                    initialTarget = initialTarget
                )
            }
        }
    }

    override fun onDestroy() {
        val config = APPConfig.getInstance(this)
        config.eventBroadcaster.removeListener(callEndedListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun endCallAndFinish(config: APPConfig, targetDevice: String? = null) {
        // Fire vaca_call_ended so the remote side also exits. Include target_device
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("caller_uuid", config.uuid)
                    put("target_device", targetDevice ?: "")
                }
                AuthUtils.haPostEvent(
                    AuthUtils.getHAUrl(config, false),
                    "vaca_call_ended",
                    json.toString(),
                    config.accessToken,
                    !config.ignoreSSLErrors
                )
            } catch (_: Exception) {}
        }
        // Only notify local state change — do NOT re-emit vaca_call_ended here if we are reacting to one
        config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", true))
        config.eventBroadcaster.notifyEvent(Event("resumeAudioInput", "", true))
        finish()
    }

    private fun extractTargetFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val byExtra = intent.getStringExtra("target_device")
        if (!byExtra.isNullOrEmpty()) return byExtra
        val data = intent.data
        if (data != null) {
            val targetQuery = data.getQueryParameter("target")
            if (!targetQuery.isNullOrEmpty()) return targetQuery
            val segments = data.pathSegments
            if (segments.isNotEmpty()) return segments.last()
        }
        return null
    }
}

// ---------------------------------------------------------------------------
//  Helper: create a WebRTCClient, initialise, create peer connection
// ---------------------------------------------------------------------------
private suspend fun createWebRtcClient(
    ctx: android.content.Context,
    config: APPConfig,
    peerId: String,
    signaling: HASignalingClient?
): WebRTCClient = withContext(Dispatchers.Default) {
    val client = WebRTCClient(ctx.applicationContext, object : WebRTCListener {
        override fun onLocalSdp(type: String, sdp: String) {
            when {
                type.equals("offer", true)  -> signaling?.sendOffer(config.uuid, peerId, sdp)
                type.equals("answer", true) -> signaling?.sendAnswer(config.uuid, peerId, sdp)
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            val obj = JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            }
            signaling?.sendIce(config.uuid, peerId, obj)
        }

        override fun onRemoteStreamAvailable() {}
    })
    client.init()
    client.createPeerConnection()
    client
}

@Composable
fun VideoCallScreen(onBack: (String?) -> Unit, initialTarget: String? = null) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val config = APPConfig.getInstance(ctx)
    val scope = rememberCoroutineScope()

    var callingTarget by remember { mutableStateOf<String?>(null) }
    var isInCall by remember { mutableStateOf(false) }

    // Hold renderers
    var localRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // WebRTC and signaling
    var signalingClient by remember { mutableStateOf<HASignalingClient?>(null) }
    var webRtcClient by remember { mutableStateOf<WebRTCClient?>(null) }

    // HA signaling listener (answers/ice only — offers go through ForegroundService)
    val haListener = remember {
        object : HASignalingListener {
            override fun onOfferReceived(data: JSONObject) {
                // Offers handled by ForegroundService → dashboard overlay
            }

            override fun onAnswerReceived(data: JSONObject) {
                try {
                    val target = data.getString("target_device")
                    if (target != config.uuid) return
                    val sdp = data.getString("sdp")
                    scope.launch { webRtcClient?.setRemoteDescription("answer", sdp) }
                } catch (e: Exception) { Logger().e("Answer handling error: $e") }
            }

            override fun onIceReceived(data: JSONObject) {
                try {
                    val target = data.getString("target_device")
                    if (target != config.uuid) return
                    val cand = data.getJSONObject("candidate")
                    val sdp = cand.getString("candidate")
                    val sdpMid = cand.optString("sdpMid", null)
                    val sdpMLineIndex = cand.optInt("sdpMLineIndex", 0)
                    val ice = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                    scope.launch { webRtcClient?.addRemoteIce(ice) }
                } catch (e: Exception) { Logger().e("ICE handling error: $e") }
            }

            override fun onCallEndedReceived(data: JSONObject) {
                // Handled by ForegroundService → callEnded event → callEndedListener.
                // Do NOT call onBack() here — that fires another vaca_call_ended and causes an infinite loop.
                scope.launch {
                    webRtcClient?.dispose()
                    webRtcClient = null
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Main setup
    // -----------------------------------------------------------------------
    LaunchedEffect(Unit) {
        val sig = HASignalingClient(config, haListener)
        signalingClient = sig
        sig.connect()

        // --- Outgoing call (initial target from vaca_start_call) -----------
        if (!initialTarget.isNullOrEmpty()) {
            callingTarget = initialTarget

            val webrtc = createWebRtcClient(ctx, config, initialTarget, sig)
            webRtcClient = webrtc
            remoteRendererRef?.let { webrtc.setRemoteRenderer(it) }
            localRendererRef?.let { webrtc.startLocalVideo(it) }
            webrtc.createOffer()

            config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", false))
            config.eventBroadcaster.notifyEvent(Event("pauseAudioInput", "", true))
            isInCall = true
        }

        // --- Incoming call (auto-accept from in-app overlay) ---------------
        val act = ctx as? ComponentActivity
        val auto = act?.intent?.getBooleanExtra("auto_accept", false) ?: false
        if (auto) {
            val caller = act?.intent?.getStringExtra("incoming_caller")
            val sdp = act?.intent?.getStringExtra("offer_sdp")
            if (!caller.isNullOrEmpty() && !sdp.isNullOrEmpty()) {
                try {
                    val webrtc = createWebRtcClient(ctx, config, caller, sig)
                    webRtcClient = webrtc
                    remoteRendererRef?.let { webrtc.setRemoteRenderer(it) }
                    localRendererRef?.let { webrtc.startLocalVideo(it) }
                    webrtc.setRemoteDescription("offer", sdp)
                    webrtc.createAnswer()

                    isInCall = true
                    callingTarget = caller
                    config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", false))
                    config.eventBroadcaster.notifyEvent(Event("pauseAudioInput", "", true))
                } catch (e: Exception) { Logger().e("Auto-accept error: $e") }
                act.intent.removeExtra("auto_accept")
            }
        }
    }

    // Renderer attachment (watches both renderer ref AND webrtc client)
    LaunchedEffect(localRendererRef, webRtcClient) {
        val renderer = localRendererRef ?: return@LaunchedEffect
        val client = webRtcClient ?: return@LaunchedEffect
        client.startLocalVideo(renderer)
    }

    LaunchedEffect(remoteRendererRef, webRtcClient) {
        val renderer = remoteRendererRef ?: return@LaunchedEffect
        val client = webRtcClient ?: return@LaunchedEffect
        client.setRemoteRenderer(renderer)
    }

    // Lifecycle cleanup
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                webRtcClient?.dispose()
                webRtcClient = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // -----------------------------------------------------------------------
    //  UI — adapts to video or audio-only mode
    // -----------------------------------------------------------------------
    Box(modifier = Modifier.fillMaxSize()) {
        // Remote video — full screen
        AndroidView(
            factory = { ctxView ->
                SurfaceViewRenderer(ctxView).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    remoteRendererRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Local preview — rounded corner, top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(160.dp, 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.DarkGray)
        ) {
            AndroidView(
                factory = { ctxView ->
                    SurfaceViewRenderer(ctxView).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setZOrderMediaOverlay(true)
                        localRendererRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // End call button — red circle, bottom center
        if (isInCall) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
            ) {
                IconButton(
                    onClick = { onBack(callingTarget) },
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                ) {
                    Text(
                        "\u2715",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

