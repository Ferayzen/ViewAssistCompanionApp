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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import android.widget.FrameLayout
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = APPConfig.getInstance(this)
        config.eventBroadcaster.addListener(callEndedListener)

        // Keep the screen active while a call is ongoing
        try {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
        // Also notify MainActivity to set screenAlwaysOn in case that code path is active
        config.eventBroadcaster.notifyEvent(Event("screenAlwaysOn", "", true))

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
        // Restore screen setting
        try { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
        config.eventBroadcaster.notifyEvent(Event("screenAlwaysOn", "", config.screenAlwaysOn))
        super.onDestroy()   // triggers composable disposal → renderers.release() + WebRTC.dispose() + signaling.close()
        // Fire resume/enable immediately — BackgroundTask handles the internal delay
        // before actually reopening the mic, so a rapid new pauseAudioInput can cancel it.
        config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", true))
        config.eventBroadcaster.notifyEvent(Event("resumeAudioInput", "", true))
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
        // Cleanup (WebRTC dispose, resumeAudioInput) handled by onDestroy → composable lifecycle
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

        override fun onAudioHealthCheckFailed() {
            Logger().e("Audio health check failed — WebRTC mic broken, scheduling process restart")
            // Show brief feedback then restart the process.
            // ForegroundService uses START_STICKY + watchdog → auto-recovery.
            try {
                Toast.makeText(ctx, "Audio error detected — restarting…", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Runtime.getRuntime().exit(1)
            }, 1500)
        }
    })
    try {
        client.init()
        client.createPeerConnection()
        // Check for cancellation after synchronous init completes.
        // If the coroutine was cancelled while the blocking code above ran,
        // this throws CancellationException so the catch block can clean up.
        ensureActive()
    } catch (e: Exception) {
        // Dispose everything that was initialised (factory, EGL, audio module, etc.)
        // before propagating the error.  Without this, a partially-initialised client
        // leaks the native audio device module and corrupts WebRTC state for future calls.
        client.dispose()
        throw e
    }
    client
}

@Composable
fun VideoCallScreen(onBack: (String?) -> Unit, initialTarget: String? = null) {
    val ctx = LocalContext.current
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
                // Handled by ForegroundService → callEnded event → callEndedListener → finish().
                // WebRTC disposal is handled by composable lifecycle (DisposableEffect).
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

            // Pause background audio BEFORE creating WebRTC so the mic is free
            // (needed on Android < 10 which lacks concurrent audio capture)
            config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", false))
            config.eventBroadcaster.notifyEvent(Event("pauseAudioInput", "", true))
            delay(600)  // give BackgroundTask time to release the mic

            try {
                val webrtc = createWebRtcClient(ctx, config, initialTarget, sig)
                webRtcClient = webrtc
                remoteRendererRef?.let { webrtc.setRemoteRenderer(it) }
                localRendererRef?.let { webrtc.startLocalVideo(it) }
                webrtc.createOffer()
                isInCall = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine cancelled (activity finishing) — createWebRtcClient
                // already disposed the client.  Re-throw so structured concurrency works.
                throw e
            } catch (e: Exception) {
                Logger().e("Outgoing call setup error: $e")
                // createWebRtcClient already disposed the partially-init'd client.
                // Resume audio so the assistant isn't permanently muted.
                config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", true))
                config.eventBroadcaster.notifyEvent(Event("resumeAudioInput", "", true))
            }
        }

        // --- Incoming call (auto-accept from in-app overlay) ---------------
        val act = ctx as? ComponentActivity
        val auto = act?.intent?.getBooleanExtra("auto_accept", false) ?: false
        if (auto) {
            val caller = act?.intent?.getStringExtra("incoming_caller")
            val sdp = act?.intent?.getStringExtra("offer_sdp")
            if (!caller.isNullOrEmpty() && !sdp.isNullOrEmpty()) {
                try {
                    // Pause background audio BEFORE creating WebRTC
                    config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", false))
                    config.eventBroadcaster.notifyEvent(Event("pauseAudioInput", "", true))
                    delay(600)  // give BackgroundTask time to release the mic

                    val webrtc = createWebRtcClient(ctx, config, caller, sig)
                    webRtcClient = webrtc
                    remoteRendererRef?.let { webrtc.setRemoteRenderer(it) }
                    localRendererRef?.let { webrtc.startLocalVideo(it) }
                    webrtc.setRemoteDescription("offer", sdp)
                    webrtc.createAnswer()

                    isInCall = true
                    callingTarget = caller
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger().e("Auto-accept error: $e")
                    config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", true))
                    config.eventBroadcaster.notifyEvent(Event("resumeAudioInput", "", true))
                }
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

    // Cleanup: release renderers, dispose WebRTC, close signaling when composable is destroyed
    DisposableEffect(Unit) {
        onDispose {
            // Capture references — composable state is torn down after onDispose
            val rtc = webRtcClient
            val sig = signalingClient
            val localR = localRendererRef
            val remoteR = remoteRendererRef
            webRtcClient = null
            signalingClient = null

            // Release renderers on the main thread (they're Views, must be on UI thread)
            rtc?.releaseRenderers(localR, remoteR)

            // Dispose WebRTC on a background thread — heavy native teardown
            // (PC dispose, factory dispose, EGL release) must not run on
            // the main thread or it can block/deadlock during Activity teardown.
            Thread {
                try {
                    rtc?.dispose()
                } catch (_: Exception) {}
                try {
                    sig?.close()
                } catch (_: Exception) {}
            }.start()
        }
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

