package com.msp1974.vacompanion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import org.webrtc.SurfaceViewRenderer
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.AuthUtils
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.webrtc.WebRTCClient
import com.msp1974.vacompanion.webrtc.WebRTCListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.IceCandidate
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Tracks the outgoing-call lifecycle visible to the caller. */
private enum class OutgoingCallState { CONNECTING, RINGING, IN_CALL, NO_ANSWER, BUSY }
private const val MAX_SDP_CHARS = 100_000

class VideoCallActivity : ComponentActivity() {
    private val log = Logger()

    // Listen for "callEnded" / "callRinging" events
    private val callEndedListener = object : EventListener {
        override fun onEventTriggered(event: Event) {
            when (event.eventName) {
                "callEnded" -> {
                    log.d("Received callEnded event — finishing VideoCallActivity")
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = APPConfig.getInstance(this)
        config.eventBroadcaster.addListener(callEndedListener)

        // Check permissions before proceeding
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            Toast.makeText(this, "Microphone permission required for calls", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!hasCam) {
            // Camera is optional — proceed with audio-only but warn
            Toast.makeText(this, "Camera permission not granted — audio only", Toast.LENGTH_LONG).show()
        }

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
                    !config.ignoreSSLErrors,
                    config
                )
            } catch (_: Exception) {}
        }
        // Cleanup (WebRTC dispose, resumeAudioInput) handled by onDestroy → composable lifecycle
        finish()
    }

    private fun extractTargetFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val byExtra = intent.getStringExtra("target_device")
        if (!byExtra.isNullOrEmpty()) {
            return if (isValidDeviceId(byExtra)) byExtra else null
        }
        val data = intent.data
        if (data != null) {
            val targetQuery = data.getQueryParameter("target")
            if (!targetQuery.isNullOrEmpty()) {
                return if (isValidDeviceId(targetQuery)) targetQuery else null
            }
            val segments = data.pathSegments
            if (segments.isNotEmpty()) {
                val candidate = segments.last()
                return if (isValidDeviceId(candidate)) candidate else null
            }
        }
        return null
    }

    private fun isValidDeviceId(value: String): Boolean {
        // UUIDs are expected; keep validation permissive enough for legacy IDs.
        return value.length in 8..64 && value.matches(Regex("^[a-zA-Z0-9_-]+$"))
    }
}

private suspend fun pauseBackgroundAudioForCall(config: APPConfig, timeoutMs: Long = 4000): Boolean {
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            var resolved = false
            val listener = object : EventListener {
                override fun onEventTriggered(event: Event) {
                    if (event.eventName != "audioInputPaused" || resolved) return
                    resolved = true
                    config.eventBroadcaster.removeListener(this)
                    continuation.resume(true)
                }
            }

            continuation.invokeOnCancellation {
                config.eventBroadcaster.removeListener(listener)
            }

            config.eventBroadcaster.addListener(listener)
            config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", false))
            config.eventBroadcaster.notifyEvent(Event("pauseAudioInput", "", true))
        }
    } ?: false
}

// ---------------------------------------------------------------------------
//  Helper: create a WebRTCClient, initialise, create peer connection.
//  Uses the service's signaling client via event bus (no direct reference).
// ---------------------------------------------------------------------------
private suspend fun createWebRtcClient(
    ctx: android.content.Context,
    config: APPConfig,
    peerId: String
): WebRTCClient = withContext(Dispatchers.Default) {
    val client = WebRTCClient(ctx.applicationContext, object : WebRTCListener {
        override fun onLocalSdp(type: String, sdp: String) {
            // Send via REST (same as before) — the service's signaling client
            // picks up the answer/ICE via WebSocket and routes to us via events.
            when {
                type.equals("offer", true) -> {
                    AuthUtils.haPostEvent(
                        AuthUtils.getHAUrl(config, false),
                        "vaca_webrtc_offer",
                        JSONObject().apply {
                            put("caller_uuid", config.uuid)
                            put("target_device", peerId)
                            put("sdp", sdp)
                        }.toString(),
                        config.accessToken,
                        !config.ignoreSSLErrors,
                        config
                    )
                }
                type.equals("answer", true) -> {
                    AuthUtils.haPostEvent(
                        AuthUtils.getHAUrl(config, false),
                        "vaca_webrtc_answer",
                        JSONObject().apply {
                            put("caller_uuid", config.uuid)
                            put("target_device", peerId)
                            put("sdp", sdp)
                        }.toString(),
                        config.accessToken,
                        !config.ignoreSSLErrors,
                        config
                    )
                }
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            val obj = JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            }
            AuthUtils.haPostEvent(
                AuthUtils.getHAUrl(config, false),
                "vaca_webrtc_ice",
                JSONObject().apply {
                    put("caller_uuid", config.uuid)
                    put("target_device", peerId)
                    put("candidate", obj)
                }.toString(),
                config.accessToken,
                !config.ignoreSSLErrors,
                config
            )
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
    var outgoingCallState by remember { mutableStateOf(OutgoingCallState.CONNECTING) }

    // Hold renderers
    var localRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // WebRTC only — no per-activity signaling client (uses service's via events)
    var webRtcClient by remember { mutableStateOf<WebRTCClient?>(null) }

    // Listen for callRinging / callBusy / webrtcAnswer / webrtcIce events
    // routed by ForegroundService from the single signaling WebSocket.
    DisposableEffect(Unit) {
        val listener = object : EventListener {
            override fun onEventTriggered(event: Event) {
                when (event.eventName) {
                    "callRinging" -> {
                        outgoingCallState = OutgoingCallState.RINGING
                    }
                    "callBusy" -> {
                        outgoingCallState = OutgoingCallState.BUSY
                    }
                    "webrtcAnswer" -> {
                        try {
                            val data = JSONObject(event.newValue as String)
                            val sdp = data.getString("sdp")
                            outgoingCallState = OutgoingCallState.IN_CALL
                            isInCall = true
                            scope.launch { webRtcClient?.setRemoteDescription("answer", sdp) }
                        } catch (e: Exception) { Logger().e("Answer handling error: $e") }
                    }
                    "webrtcIce" -> {
                        try {
                            val data = JSONObject(event.newValue as String)
                            val cand = data.getJSONObject("candidate")
                            val sdp = cand.getString("candidate")
                            val sdpMid = cand.optString("sdpMid", null)
                            val sdpMLineIndex = cand.optInt("sdpMLineIndex", 0)
                            val ice = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                            scope.launch { webRtcClient?.addRemoteIce(ice) }
                        } catch (e: Exception) { Logger().e("ICE handling error: $e") }
                    }
                }
            }
        }
        config.eventBroadcaster.addListener(listener)
        onDispose { config.eventBroadcaster.removeListener(listener) }
    }

    // ---- Audio feedback for outgoing calls --------------------------------
    // CONNECTING: short beep every 3 seconds
    // RINGING: standard ringback tone (1s on, 3s off)
    // Stops when the call transitions to IN_CALL or the composable leaves.
    if (!initialTarget.isNullOrEmpty() && !isInCall) {
        DisposableEffect(outgoingCallState) {
            val toneType = when (outgoingCallState) {
                OutgoingCallState.CONNECTING -> ToneGenerator.TONE_PROP_PROMPT
                OutgoingCallState.RINGING    -> ToneGenerator.TONE_SUP_RINGTONE
                else -> -1
            }
            var toneGen: ToneGenerator? = null
            var job: kotlinx.coroutines.Job? = null

            if (toneType >= 0) {
                try {
                    toneGen = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
                } catch (_: Exception) {}

                job = scope.launch {
                    try {
                        when (outgoingCallState) {
                            OutgoingCallState.CONNECTING -> {
                                while (true) {
                                    toneGen?.startTone(ToneGenerator.TONE_PROP_PROMPT, 200)
                                    delay(3000)
                                }
                            }
                            OutgoingCallState.RINGING -> {
                                while (true) {
                                    toneGen?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1000)
                                    delay(4000) // 1s tone + 3s silence
                                }
                            }
                            else -> {}
                        }
                    } finally {
                        try { toneGen?.stopTone() } catch (_: Exception) {}
                        try { toneGen?.release() } catch (_: Exception) {}
                    }
                }
            }

            onDispose {
                job?.cancel()
                try { toneGen?.stopTone() } catch (_: Exception) {}
                try { toneGen?.release() } catch (_: Exception) {}
            }
        }
    }

    // ---- 30-second caller timeout -----------------------------------------
    // If the target hasn't answered within 30s, give up and show "No answer".
    if (!initialTarget.isNullOrEmpty() && !isInCall) {
        LaunchedEffect(Unit) {
            delay(30_000)
            if (!isInCall && outgoingCallState != OutgoingCallState.IN_CALL) {
                outgoingCallState = OutgoingCallState.NO_ANSWER
                // Wait briefly so user can see the message, then end
                delay(2000)
                onBack(initialTarget)
            }
        }
    }

    // ---- Auto-end on busy ---------------------------------------------------
    if (outgoingCallState == OutgoingCallState.BUSY) {
        LaunchedEffect(Unit) {
            delay(2000) // show "Busy" briefly
            onBack(callingTarget)
        }
    }

    // -----------------------------------------------------------------------
    //  Main setup — no per-activity signaling client; uses service's via events
    // -----------------------------------------------------------------------
    LaunchedEffect(Unit) {
        // --- Outgoing call (initial target from vaca_start_call) -----------
        if (!initialTarget.isNullOrEmpty()) {
            callingTarget = initialTarget

            // Pause background audio BEFORE creating WebRTC so the mic is free
            // (needed on Android < 10 which lacks concurrent audio capture)
            val paused = pauseBackgroundAudioForCall(config)
            if (!paused) {
                Logger().e("Timed out waiting for assistant mic release, proceeding with fallback delay")
                delay(600)
            }

            try {
                val webrtc = createWebRtcClient(ctx, config, initialTarget)
                webRtcClient = webrtc
                remoteRendererRef?.let { webrtc.setRemoteRenderer(it) }
                localRendererRef?.let { webrtc.startLocalVideo(it) }
                webrtc.createOffer()
                // Don't set isInCall yet — stay in CONNECTING state until
                // the target confirms ringing (→ RINGING) or answers (→ IN_CALL).
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
            if (!caller.isNullOrEmpty() && !sdp.isNullOrEmpty() && sdp.length <= MAX_SDP_CHARS) {
                try {
                    // Pause background audio BEFORE creating WebRTC
                    val paused = pauseBackgroundAudioForCall(config)
                    if (!paused) {
                        Logger().e("Timed out waiting for assistant mic release, proceeding with fallback delay")
                        delay(600)
                    }

                    val webrtc = createWebRtcClient(ctx, config, caller)
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
            } else if (auto) {
                Logger().e("Rejecting invalid incoming call intent payload")
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

    // Cleanup: release renderers, dispose WebRTC when composable is destroyed
    DisposableEffect(Unit) {
        onDispose {
            // Capture references — composable state is torn down after onDispose
            val rtc = webRtcClient
            val localR = localRendererRef
            val remoteR = remoteRendererRef
            webRtcClient = null

            // Release renderers on the main thread (they're Views, must be on UI thread)
            rtc?.releaseRenderers(localR, remoteR)

            // Dispose WebRTC on a background thread — heavy native teardown
            // (PC dispose, factory dispose, EGL release) must not run on
            // the main thread or it can block/deadlock during Activity teardown.
            Thread {
                try {
                    rtc?.dispose()
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

        // ---- Call-state overlay (connecting / ringing) --------------------
        if (!initialTarget.isNullOrEmpty() && !isInCall) {
            // Animated dots "..." that cycle every second
            var dotCount by remember { mutableStateOf(1) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(600)
                    dotCount = (dotCount % 3) + 1
                }
            }
            val dots = ".".repeat(dotCount)
            val statusText = when (outgoingCallState) {
                OutgoingCallState.CONNECTING -> "Connecting$dots"
                OutgoingCallState.RINGING    -> "Ringing$dots"
                OutgoingCallState.NO_ANSWER  -> "No answer"
                OutgoingCallState.BUSY       -> "Busy"
                OutgoingCallState.IN_CALL    -> ""
            }
            val statusColor = when (outgoingCallState) {
                OutgoingCallState.CONNECTING -> Color(0xFFFFA726) // orange
                OutgoingCallState.RINGING    -> Color(0xFF66BB6A) // green
                OutgoingCallState.NO_ANSWER  -> Color(0xFFE53935) // red
                OutgoingCallState.BUSY       -> Color(0xFFE53935) // red
                OutgoingCallState.IN_CALL    -> Color.White
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // End call button — red circle, bottom center
        // Visible always for outgoing calls (connecting/ringing/in-call)
        // and once connected for incoming calls.
        if (isInCall || !initialTarget.isNullOrEmpty()) {
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

