package com.msp1974.vacompanion.ui.layouts

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.DiagnosticBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WebViewScreen (webView: WebView, vaViewModel: VAViewModel = viewModel()) {
    val vaUiState by vaViewModel.vacaState.collectAsState()

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

        // Signaling status overlay for debugging while the dashboard is shown
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

                // show a small preview of the last 3 log lines
                if (!showLog) {
                    val preview = vaUiState.signalingLog.takeLast(3)
                    preview.forEach { line ->
                        Text(text = line, color = Color.White, fontSize = 10.sp)
                    }
                } else {
                    // full log view
                    Column(modifier = Modifier) {
                        vaUiState.signalingLog.forEach { line ->
                            Text(text = line, color = Color.White, fontSize = 10.sp)
                        }
                    }
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



