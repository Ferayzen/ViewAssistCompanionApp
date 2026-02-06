package com.msp1974.vacompanion.ui.layouts

import android.content.Intent
import android.media.RingtoneManager
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.msp1974.vacompanion.service.VAForegroundService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.VideoCallActivity
import com.msp1974.vacompanion.ui.components.DiagnosticBar
import com.msp1974.vacompanion.utils.AuthUtils
import com.msp1974.vacompanion.utils.Event
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun WebViewScreen (webView: WebView, vaViewModel: VAViewModel = viewModel()) {
    val vaUiState by vaViewModel.vacaState.collectAsState()
    val ctx = LocalContext.current
    val config = APPConfig.getInstance(ctx)
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        var modifier = Modifier
            .fillMaxSize()
            .background(if(vaUiState.satelliteRunning) Color.Black else MaterialTheme.colorScheme.background)

        if (vaUiState.isDND) {
            modifier = modifier.border(4.dp, Color.Red)
        }

        Box(modifier = modifier) {
            WebView(webView, swipeRefreshEnabled = vaViewModel.config!!.swipeRefresh)
        }

        if (vaUiState.webViewPageLoadingStage != PageLoadingStage.LOADED && false) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Loading...", color = Color.White, fontSize = MaterialTheme.typography.headlineLarge.fontSize, textAlign = TextAlign.Center)
                }
            }
        }

        if (vaUiState.diagnosticInfo.show) {
            DiagnosticBar(
                vaUiState.diagnosticInfo,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // ----- Debug overlay (conditional on flag) -------------------------
        if (VAForegroundService.DEBUG_OVERLAY) {
            var showLog by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    val statusText = if (vaUiState.signalingConnected) "Signaling: Connected" else "Signaling: Disconnected"
                    Text(
                        text = statusText,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showLog = !showLog }
                    )
                    if (!showLog) {
                        val preview = vaUiState.signalingLog.takeLast(3)
                        preview.forEach { line ->
                            Text(text = line, color = Color.White, fontSize = 10.sp)
                        }
                    } else {
                        Column {
                            vaUiState.signalingLog.forEach { line ->
                                Text(text = line, color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // ----- Incoming call overlay ---------------------------------------
        val showIncoming = vaUiState.incomingCallCaller != null
        AnimatedVisibility(
            visible = showIncoming,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            IncomingCallOverlay(
                caller = vaUiState.incomingCallCaller ?: "",
                onAccept = {
                    val caller = vaUiState.incomingCallCaller ?: return@IncomingCallOverlay
                    val sdp = vaUiState.incomingCallSdp ?: return@IncomingCallOverlay
                    // Dismiss overlay
                    config.eventBroadcaster.notifyEvent(Event("incomingCallDismiss", "", ""))
                    // Launch VideoCallActivity with auto-accept
                    val intent = Intent(ctx, VideoCallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("incoming_caller", caller)
                        putExtra("offer_sdp", sdp)
                        putExtra("auto_accept", true)
                    }
                    ctx.startActivity(intent)
                },
                onDecline = {
                    val caller = vaUiState.incomingCallCaller
                    // Dismiss overlay
                    config.eventBroadcaster.notifyEvent(Event("incomingCallDismiss", "", ""))
                    // Fire decline event to HA
                    if (caller != null) {
                        scope.launch {
                            try {
                                val json = JSONObject().apply {
                                    put("caller_uuid", caller)
                                    put("target_device", config.uuid)
                                }
                                AuthUtils.haPostEvent(
                                    AuthUtils.getHAUrl(config, false),
                                    "vaca_call_declined",
                                    json.toString(),
                                    config.accessToken,
                                    !config.ignoreSSLErrors
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
            )
        }

        // Auto-dismiss incoming call after 20 seconds
        LaunchedEffect(vaUiState.incomingCallCaller) {
            if (vaUiState.incomingCallCaller != null) {
                delay(20_000)
                // If still showing, auto-decline
                if (vaUiState.incomingCallCaller != null) {
                    val caller = vaUiState.incomingCallCaller
                    config.eventBroadcaster.notifyEvent(Event("incomingCallDismiss", "", ""))
                    if (caller != null) {
                        try {
                            val json = JSONObject().apply {
                                put("caller_uuid", caller)
                                put("target_device", config.uuid)
                            }
                            AuthUtils.haPostEvent(
                                AuthUtils.getHAUrl(config, false),
                                "vaca_call_declined",
                                json.toString(),
                                config.accessToken,
                                !config.ignoreSSLErrors
                            )
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }
}


@Composable
private fun IncomingCallOverlay(
    caller: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val ctx = LocalContext.current

    // Play ringtone while overlay is visible; stop on dispose (accept/decline/timeout)
    DisposableEffect(Unit) {
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtone = RingtoneManager.getRingtone(ctx, ringtoneUri)
        ringtone?.play()
        onDispose {
            try { ringtone?.stop() } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Caller icon placeholder
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = caller.take(2).uppercase(),
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Incoming Call",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = caller,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Accept / Decline buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(64.dp)
            ) {
                // Decline button (red circle)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935))
                    ) {
                        Text("\u2715", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Decline", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }

                // Accept button (green circle)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = onAccept,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF43A047))
                    ) {
                        Text("\u2713", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Accept", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        }
    }
}


@Composable
fun WebView(
    webView: WebView,
    modifier: Modifier = Modifier,
    swipeRefreshEnabled: Boolean = true,
) {
    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier
            .fillMaxSize(),
        factory = { context ->
            SwipeRefreshLayout(context).apply {
                setOnRefreshListener {
                    refreshScope.launch {
                        refreshing = true
                        webView.reload()
                        delay(1500)
                        refreshing = false
                    }
                }
                if (webView.parent != null) {
                    (webView.parent as ViewGroup).removeView(webView)
                }
                addView(webView).apply {
                    tag = "vaWebView"
                }
            }
        },
        update = { view ->
            view.isRefreshing = refreshing
            view.isEnabled = swipeRefreshEnabled
        }
    )
}



