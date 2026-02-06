package com.msp1974.vacompanion.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.webrtc.SurfaceViewRenderer
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.AuthUtils
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.webrtc.HASignalingClient
import com.msp1974.vacompanion.webrtc.HASignalingListener
import com.msp1974.vacompanion.webrtc.WebRTCClient
import com.msp1974.vacompanion.webrtc.WebRTCListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.json.JSONArray
import org.json.JSONObject

class VideoCallActivity : ComponentActivity() {
    private val log = Logger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract possible incoming target from intent (deep link or action)
        val initialTarget = extractTargetFromIntent(intent)

        setContent {
            val ctx = LocalContext.current
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                VideoCallScreen(onBack = { finish() }, initialTarget = initialTarget)
            }
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // If new intent contains a start-call action or target param, handle it
        val target = extractTargetFromIntent(intent)
        if (!target.isNullOrEmpty()) {
            lifecycleScope.launch {
                val cfg = APPConfig.getInstance(this@VideoCallActivity)
                startCallById(cfg, target)
                cfg.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", false))
            }
        }
    }

    private fun extractTargetFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        // check extras first
        val byExtra = intent.getStringExtra("target_device")
        if (!byExtra.isNullOrEmpty()) return byExtra
        // check data URI: vaca://connect/video?target=<id>
        val data = intent.data
        if (data != null) {
            val targetQuery = data.getQueryParameter("target")
            if (!targetQuery.isNullOrEmpty()) return targetQuery
            // also allow vaca://connect/video/<device_id>
            val segments = data.pathSegments
            if (segments.isNotEmpty()) {
                // last segment as id
                return segments.last()
            }
        }
        return null
    }
}

@Composable
fun VideoCallScreen(onBack: () -> Unit, initialTarget: String? = null) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val config = APPConfig.getInstance(ctx)
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf<List<Map<String, String>>>(listOf()) }
    var callingTarget by remember { mutableStateOf<String?>(null) }
    var isInCall by remember { mutableStateOf(false) }

    // Incoming call state
    var incomingCaller by remember { mutableStateOf<String?>(null) }
    var incomingOffer by remember { mutableStateOf<String?>(null) }

    // Hold renderers
    var localRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // WebRTC and signaling
    var signalingClient by remember { mutableStateOf<HASignalingClient?>(null) }
    var webRtcClient by remember { mutableStateOf<WebRTCClient?>(null) }
    var currentPeerId by remember { mutableStateOf<String?>(null) }

    // HA signaling listener
    val haListener = remember {
        object : HASignalingListener {
            override fun onOfferReceived(data: JSONObject) {
                try {
                    val target = data.getString("target_device")
                    if (target != config.uuid) return
                    val caller = data.getString("caller_uuid")
                    val sdp = data.getString("sdp")

                    // Show incoming call UI and notify (don't auto-answer)
                    scope.launch {
                        incomingCaller = caller
                        incomingOffer = sdp
                    }
                } catch (e: Exception) { Logger().e("Offer handling error: $e") }
            }

            override fun onAnswerReceived(data: JSONObject) {
                try {
                    val target = data.getString("target_device")
                    if (target != config.uuid) return
                    val sdp = data.getString("sdp")
                    scope.launch {
                        webRtcClient?.setRemoteDescription("answer", sdp)
                    }
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
        }
    }

    LaunchedEffect(Unit) {
        scope.launch {
            // Connect signaling client to listen for offers/answers/ice
            signalingClient = HASignalingClient(config, haListener)
            signalingClient?.connect()

            val list = fetchDevices(config)
            devices = list

            // Auto start if initial target provided
            if (!initialTarget.isNullOrEmpty()) {
                callingTarget = initialTarget
                currentPeerId = initialTarget

                // Create local WebRTC client and start call
                val webrtc = WebRTCClient(ctx, object : WebRTCListener {
                    override fun onLocalSdp(type: String, sdp: String) {
                        signalingClient?.sendOffer(config.uuid, currentPeerId!!, sdp)
                    }

                    override fun onIceCandidate(candidate: IceCandidate) {
                        val obj = JSONObject()
                        obj.put("sdpMid", candidate.sdpMid)
                        obj.put("sdpMLineIndex", candidate.sdpMLineIndex)
                        obj.put("candidate", candidate.sdp)
                        signalingClient?.sendIce(config.uuid, currentPeerId!!, obj)
                    }

                    override fun onRemoteStreamAvailable() {}
                })
                webRtcClient = webrtc
                webrtc.init()
                webrtc.createPeerConnection()
                // Attach renderers if ready
                remoteRendererRef?.let { webrtc.setRemoteRenderer(it) }
                localRendererRef?.let { webrtc.startLocalVideo(it) }
                webrtc.createOffer()

                config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", false))
                isInCall = true
            }
        }
    }

    // Auto-accept when started via CallActionReceiver extras
    LaunchedEffect(Unit) {
        val act = ctx as? ComponentActivity
        val auto = act?.intent?.getBooleanExtra("auto_accept", false) ?: false
        if (auto) {
            val caller = act?.intent?.getStringExtra("incoming_caller")
            val sdp = act?.intent?.getStringExtra("offer_sdp")
            if (!caller.isNullOrEmpty() && !sdp.isNullOrEmpty()) {
                // Accept the call automatically
                incomingCaller = caller
                incomingOffer = sdp
                // perform accept
                try {
                    acceptIncomingCall(ctx.applicationContext, config, caller, sdp, webRtcClient, { w -> webRtcClient = w }, localRendererRef, remoteRendererRef, signalingClient)
                    isInCall = true
                    callingTarget = caller
                } catch (e: Exception) { Logger().e("Auto-accept error: $e") }
                // prevent repeated auto-accept
                act.intent.removeExtra("auto_accept")
            }
        }
    }

    // Lifecycle cleanup
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY || event == Lifecycle.Event.ON_PAUSE) {
                webRtcClient?.dispose()
                webRtcClient = null
                // keep signaling connected for incoming calls; close only on destroy
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // When a local renderer becomes available, start local video if webrtc client exists
    LaunchedEffect(localRendererRef) {
        if (localRendererRef != null) {
            webRtcClient?.startLocalVideo(localRendererRef!!)
        }
    }

    // When remote renderer becomes available, attach remote sink
    LaunchedEffect(remoteRendererRef) {
        if (remoteRendererRef != null) {
            webRtcClient?.setRemoteRenderer(remoteRendererRef!!)
        }
    }

    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.Top) {
        Text(text = "Video Call — Available devices", style = MaterialTheme.typography.titleMedium)

        // Remote video large view
        AndroidView(
            factory = { ctxView ->
                SurfaceViewRenderer(ctxView).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 480)
                    id = android.R.id.background
                    remoteRendererRef = this
                    // when renderer is ready, attach to webRtcClient if present
                    if (webRtcClient != null) webRtcClient?.setRemoteRenderer(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Local preview small overlay
        AndroidView(factory = { ctxView ->
            SurfaceViewRenderer(ctxView).apply {
                layoutParams = FrameLayout.LayoutParams(320, 240)
                localRendererRef = this
                if (webRtcClient != null) webRtcClient?.startLocalVideo(this)
            }
        })

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { device ->
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(text = device["name"] ?: device["id"].orEmpty())
                    Text(text = device["id"].orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        // start full call flow
                        scope.launch {
                            currentPeerId = device["id"]
                            // Create webRTC client for outgoing call
                            val webrtc = WebRTCClient(ctx, object : WebRTCListener {
                                override fun onLocalSdp(type: String, sdp: String) {
                                    signalingClient?.sendOffer(config.uuid, currentPeerId!!, sdp)
                                }

                                override fun onIceCandidate(candidate: IceCandidate) {
                                    val obj = JSONObject()
                                    obj.put("sdpMid", candidate.sdpMid)
                                    obj.put("sdpMLineIndex", candidate.sdpMLineIndex)
                                    obj.put("candidate", candidate.sdp)
                                    signalingClient?.sendIce(config.uuid, currentPeerId!!, obj)
                                }

                                override fun onRemoteStreamAvailable() {}
                            })
                            webRtcClient = webrtc
                            webrtc.init()
                            webrtc.createPeerConnection()
                            remoteRendererRef?.let { webrtc.setRemoteRenderer(it) }
                            localRendererRef?.let { webrtc.startLocalVideo(it) }
                            webrtc.createOffer()

                            config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", false))
                            isInCall = true
                            callingTarget = currentPeerId
                            Toast.makeText(ctx, "Calling ${device["name"]}", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text(text = "Call")
                    }
                }
            }
        }

        if (isInCall) {
            Text(text = "In Call with ${callingTarget}")
            Button(onClick = {
                // End call
                webRtcClient?.dispose()
                webRtcClient = null
                isInCall = false
                callingTarget = null
                currentPeerId = null
                config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", true))
                Toast.makeText(ctx, "Call ended", Toast.LENGTH_SHORT).show()
            }) {
                Text("End Call")
            }
        }

        // Incoming call dialog
        if (!incomingCaller.isNullOrEmpty()) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { /* don't dismiss by tapping outside */ },
                title = { Text("Incoming Call") },
                text = { Text("Incoming call from ${incomingCaller}") },
                confirmButton = {
                    Button(onClick = {
                        // Accept
                        scope.launch {
                            incomingCaller?.let { caller ->
                                incomingOffer?.let { sdp ->
                                    acceptIncomingCall(ctx.applicationContext, config, caller, sdp, webRtcClient, { w -> webRtcClient = w }, localRendererRef, remoteRendererRef, signalingClient)
                                    // set UI state
                                    isInCall = true
                                    callingTarget = caller
                                }
                            }
                            incomingCaller = null
                            incomingOffer = null
                        }
                    }) { Text("Accept") }
                },
                dismissButton = {
                    Button(onClick = {
                        // Decline
                        scope.launch {
                            incomingCaller?.let { caller ->
                                val json = JSONObject()
                                json.put("caller_uuid", caller)
                                json.put("target_device", config.uuid)
                                AuthUtils.haPostEvent(AuthUtils.getHAUrl(config, false), "vaca_call_declined", json.toString(), config.accessToken, !config.ignoreSSLErrors)
                            }
                            incomingCaller = null
                            incomingOffer = null
                        }
                    }) { Text("Decline") }
                }
            )
        }
    }
}



suspend fun fetchDevices(config: APPConfig): List<Map<String, String>> {
    return withContext(Dispatchers.IO) {
        try {
            val url = AuthUtils.getHAUrl(config, withDashboardPath = false) + "/api/states"
            val resp = AuthUtils.haGet(url, config, !config.ignoreSSLErrors)
            val arr = JSONArray(resp)
            val devices = mutableListOf<Map<String, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val attrs = obj.optJSONObject("attributes") ?: JSONObject()
                val friendly = attrs.optString("friendly_name", "")
                val deviceSignature = attrs.optString("device_signature", "")
                val name = if (friendly.isNotEmpty()) friendly else obj.optString("entity_id")
                // Heuristic: include entities that look like VACA devices (device_signature present or name contains VACA)
                if (deviceSignature.isNotEmpty() || name.contains("VACA", ignoreCase = true) || obj.optString("entity_id").contains("camera")) {
                    devices.add(mapOf("id" to (deviceSignature.ifEmpty { obj.optString("entity_id") }), "name" to name))
                }
            }
            devices
        } catch (e: Exception) {
            Logger().e("Error fetching devices: $e")
            listOf()
        }
    }
}

suspend fun startCall(config: APPConfig, device: Map<String, String>, callerId: String) {
    withContext(Dispatchers.IO) {
        try {
            val event = JSONObject()
            event.put("caller_uuid", config.uuid)
            event.put("target_device", device["id"])
            // Fire a Home Assistant event as a POC. Automations in HA can pick this up to handle signaling.
            AuthUtils.haPostEvent(AuthUtils.getHAUrl(config, false), "vaca_start_call", event.toString(), config.accessToken, !config.ignoreSSLErrors)
        } catch (e: Exception) {
            Logger().e("Error starting call: $e")
        }
    }
}

suspend fun startCallById(config: APPConfig, deviceId: String) {
    withContext(Dispatchers.IO) {
        try {
            val event = JSONObject()
            event.put("caller_uuid", config.uuid)
            event.put("target_device", deviceId)
            AuthUtils.haPostEvent(AuthUtils.getHAUrl(config, false), "vaca_start_call", event.toString(), config.accessToken, !config.ignoreSSLErrors)
            Logger().d("Started call to $deviceId")
        } catch (e: Exception) {
            Logger().e("Error starting call by id: $e")
        }
    }
}

// Accept an incoming call: set up WebRTC client, set remote offer, create and send answer
suspend fun acceptIncomingCall(
    ctx: android.content.Context,
    config: APPConfig,
    caller: String,
    offerSdp: String,
    existingWebRtcClient: WebRTCClient?,
    setWebRtcClient: (WebRTCClient?) -> Unit,
    localRenderer: SurfaceViewRenderer?,
    remoteRenderer: SurfaceViewRenderer?,
    signaling: HASignalingClient?
) {
    try {
        // Heavy initialization off the main thread to avoid blocking UI
        val webrtc = withContext(Dispatchers.Default) {
            val client = WebRTCClient(ctx.applicationContext, object : WebRTCListener {
                override fun onLocalSdp(type: String, sdp: String) {
                    if (type.equals("answer", ignoreCase = true)) {
                        signaling?.sendAnswer(config.uuid, caller, sdp)
                    }
                }

                override fun onIceCandidate(candidate: IceCandidate) {
                    val obj = JSONObject()
                    obj.put("sdpMid", candidate.sdpMid)
                    obj.put("sdpMLineIndex", candidate.sdpMLineIndex)
                    obj.put("candidate", candidate.sdp)
                    signaling?.sendIce(config.uuid, caller, obj)
                }

                override fun onRemoteStreamAvailable() {}
            })

            client.init()
            client.createPeerConnection()
            client
        }

        // Update caller state on main thread and attach renderers
        withContext(Dispatchers.Main) {
            setWebRtcClient(webrtc)
            try {
                remoteRenderer?.let { webrtc.setRemoteRenderer(it) }
            } catch (e: Exception) {
                Logger().e("Error attaching remote renderer: $e")
            }
            try {
                localRenderer?.let { webrtc.startLocalVideo(it) }
            } catch (e: Exception) {
                Logger().e("Error starting local video: $e")
            }
        }

        // Set remote description and create/send answer off main thread
        withContext(Dispatchers.Default) {
            try {
                webrtc.setRemoteDescription("offer", offerSdp)
                webrtc.createAnswer()
            } catch (e: Exception) {
                Logger().e("Error setting remote description or creating answer: $e")
                try { config.eventBroadcaster.notifyEvent(Event("signalingLog", "", "accept error: ${e.message}")) } catch (ex: Exception) {}
            }
        }

        // Disable motion detection while in call
        try { config.eventBroadcaster.notifyEvent(Event("enableMotionDetection", "", false)) } catch (e: Exception) {}

    } catch (e: Exception) {
        Logger().e("Error accepting call: $e")
        try { config.eventBroadcaster.notifyEvent(Event("signalingLog", "", "accept exception: ${e.message}")) } catch (ex: Exception) {}
    }
}

