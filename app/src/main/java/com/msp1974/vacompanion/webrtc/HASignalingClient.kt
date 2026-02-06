package com.msp1974.vacompanion.webrtc

import android.net.Uri
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.AuthUtils
import com.msp1974.vacompanion.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

interface HASignalingListener {
    fun onOfferReceived(data: JSONObject)
    fun onAnswerReceived(data: JSONObject)
    fun onIceReceived(data: JSONObject)
    // Optional: called when a start call event is received (no SDP) - useful to ring UI
    fun onStartCallReceived(data: JSONObject) { }
}

class HASignalingClient(private val config: APPConfig, private val listener: HASignalingListener) {
    private val log = Logger()
    private var ws: WebSocket? = null
    private val client = OkHttpClient()
    private val idCounter = AtomicInteger(1)

    // Optional status callback to notify about connection/auth state changes
    var statusCallback: ((Boolean) -> Unit)? = null
    // Optional log callback so callers can display logs in UI
    var logCallback: ((String) -> Unit)? = null

    fun connect() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val base = AuthUtils.getHAUrl(config, withDashboardPath = false)
                val wsUrl = base.removePrefix("http://").removePrefix("https://")
                val scheme = if (base.startsWith("https")) "wss" else "ws"
                val uri = "$scheme://$wsUrl/api/websocket"
                log.d("HA WS connecting to: $uri")
                try { logCallback?.invoke("Connecting to $uri") } catch (e: Exception) {}
                val request = Request.Builder().url(uri).build()
                ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        log.d("HA WebSocket opened")
                        try { logCallback?.invoke("WebSocket opened") } catch (e: Exception) {}
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val json = JSONObject(text)
                            if (json.has("type") && json.getString("type") == "auth_required") {
                                // Send auth
                                val auth = JSONObject().apply {
                                    put("type", "auth")
                                    put("access_token", config.accessToken)
                                }
                                val masked = if (config.accessToken.length > 6) "****" + config.accessToken.takeLast(6) else "****"
                                log.d("HA WS sending auth token (masked): $masked")
                                try { logCallback?.invoke("Sending auth token: $masked") } catch (e: Exception) {}
                                webSocket.send(auth.toString())
                                return
                            }

                            if (json.has("type") && json.getString("type") == "auth_ok") {
                                log.d("HA WS auth_ok received")
                                try { logCallback?.invoke("auth_ok received") } catch (e: Exception) {}
                                // Subscribe to our event types
                                subscribeEvent(webSocket, "vaca_webrtc_offer")
                                subscribeEvent(webSocket, "vaca_webrtc_answer")
                                subscribeEvent(webSocket, "vaca_webrtc_ice")
                                subscribeEvent(webSocket, "vaca_start_call")
                                try { logCallback?.invoke("Subscribed to vaca_* events") } catch (e: Exception) {}
                                // Notify that we are connected and authenticated
                                try { statusCallback?.invoke(true) } catch (e: Exception) {}
                                return
                            }
                            if (json.has("type") && json.getString("type") == "auth_invalid") {
                                log.e("HA WS auth invalid")
                                try { logCallback?.invoke("auth_invalid received") } catch (e: Exception) {}
                                try { statusCallback?.invoke(false) } catch (e: Exception) {}
                                return
                            }

                            if (json.has("type") && json.getString("type") == "event") {
                                val event = json.getJSONObject("event")
                                val eventType = event.getString("event_type")
                                val data = event.getJSONObject("data")
                                // Log all incoming events for debugging
                                try { logCallback?.invoke("Event received: $eventType => ${data.toString()}") } catch (e: Exception) {}

                                when (eventType) {
                                    "vaca_webrtc_offer" -> listener.onOfferReceived(data)
                                    "vaca_webrtc_answer" -> listener.onAnswerReceived(data)
                                    "vaca_webrtc_ice" -> listener.onIceReceived(data)
                                    "vaca_start_call" -> {
                                        // Passive start call notification - forwarded to listener if implemented
                                        try { listener.onStartCallReceived(data) } catch (e: Exception) {}
                                    }
                                    else -> {
                                        try { logCallback?.invoke("Unhandled event type: $eventType") } catch (e: Exception) {}
                                    }
                                }
                            }

                        } catch (e: Exception) {
                            log.e("HA WS parse error: $e")
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        log.d("HA WS closing: $code $reason")
                        try { logCallback?.invoke("WS closing: $code $reason") } catch (e: Exception) {}
                        try { statusCallback?.invoke(false) } catch (e: Exception) {}
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        log.e("HA WS failure: $t")
                        try { logCallback?.invoke("WS failure: ${t.message}") } catch (e: Exception) {}
                        try { statusCallback?.invoke(false) } catch (e: Exception) {}
                    }
                })
            } catch (e: Exception) {
                log.e("Error connecting to HA websocket: $e")
                try { statusCallback?.invoke(false) } catch (e: Exception) {}
            }
        }
    }

    private fun subscribeEvent(ws: WebSocket, eventType: String) {
        val id = idCounter.getAndIncrement()
        val msg = JSONObject().apply {
            put("id", id)
            put("type", "subscribe_events")
            put("event_type", eventType)
        }
        ws.send(msg.toString())
        log.d("Subscribed to HA event: $eventType")
        try { logCallback?.invoke("Subscribed to $eventType") } catch (e: Exception) {}
    }

    fun close() {
        ws?.close(1000, "closing")
    }

    // Helpers to send events via REST
    fun sendOffer(callerUuid: String, targetDevice: String, sdp: String) {
        val json = JSONObject().apply {
            put("caller_uuid", callerUuid)
            put("target_device", targetDevice)
            put("sdp", sdp)
        }
        AuthUtils.haPostEvent(AuthUtils.getHAUrl(config, false), "vaca_webrtc_offer", json.toString(), config.accessToken, !config.ignoreSSLErrors)
    }

    fun sendAnswer(callerUuid: String, targetDevice: String, sdp: String) {
        val json = JSONObject().apply {
            put("caller_uuid", callerUuid)
            put("target_device", targetDevice)
            put("sdp", sdp)
        }
        AuthUtils.haPostEvent(AuthUtils.getHAUrl(config, false), "vaca_webrtc_answer", json.toString(), config.accessToken, !config.ignoreSSLErrors)
    }

    fun sendIce(callerUuid: String, targetDevice: String, candidate: JSONObject) {
        val json = JSONObject().apply {
            put("caller_uuid", callerUuid)
            put("target_device", targetDevice)
            put("candidate", candidate)
        }
        AuthUtils.haPostEvent(AuthUtils.getHAUrl(config, false), "vaca_webrtc_ice", json.toString(), config.accessToken, !config.ignoreSSLErrors)
    }
}
