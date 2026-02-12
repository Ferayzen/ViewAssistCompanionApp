package com.msp1974.vacompanion.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.AuthUtils
import com.msp1974.vacompanion.utils.Logger
import org.json.JSONObject

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACCEPT = "com.msp1974.vacompanion.ACTION_ACCEPT_CALL"
        const val ACTION_DECLINE = "com.msp1974.vacompanion.ACTION_DECLINE_CALL"
        const val EXTRA_CALLER = "caller_uuid"
        const val EXTRA_SDP = "offer_sdp"
    }

    private val log = Logger()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val cfg = APPConfig.getInstance(context)
        try {
            when (action) {
                ACTION_ACCEPT -> {
                    // Start VideoCallActivity with auto accept
                    val caller = intent.getStringExtra(EXTRA_CALLER)
                    val sdp = intent.getStringExtra(EXTRA_SDP)
                    val startIntent = Intent(context, com.msp1974.vacompanion.ui.VideoCallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("incoming_caller", caller)
                        putExtra("offer_sdp", sdp)
                        putExtra("auto_accept", true)
                    }
                    context.startActivity(startIntent)
                    // cancel notification
                    try {
                        val notifId = 1000 + (caller?.hashCode() ?: 0) % 1000
                        androidx.core.app.NotificationManagerCompat.from(context).cancel(notifId)
                    } catch (e: Exception) { /* ignore */ }
                }
                ACTION_DECLINE -> {
                    val caller = intent.getStringExtra(EXTRA_CALLER)
                    val json = JSONObject()
                    json.put("caller_uuid", caller)
                    json.put("target_device", cfg.uuid)
                    AuthUtils.haPostEvent(AuthUtils.getHAUrl(cfg, false), "vaca_call_declined", json.toString(), cfg.accessToken, !cfg.ignoreSSLErrors, cfg)
                }
            }
        } catch (e: Exception) {
            log.e("CallActionReceiver error: $e")
        }
    }
}