package com.msp1974.vacompanion.service

import android.Manifest
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import com.msp1974.vacompanion.MainActivity
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.VACAApplication
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.BackgroundTaskStatus
import com.msp1974.vacompanion.ui.VideoCallActivity
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.webrtc.HASignalingClient
import com.msp1974.vacompanion.webrtc.HASignalingListener
import com.msp1974.vacompanion.utils.AuthUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.util.Timer
import java.util.TimerTask


class VAForegroundService : Service() {
    companion object {
        /** Set to true to enable debug toasts and signaling overlay on dashboard. */
        const val DEBUG_OVERLAY = false
    }

    private lateinit var config: APPConfig
    private var wifiLock: WifiManager.WifiLock? = null
    private var keyguardLock: KeyguardManager.KeyguardLock? = null
    private var watchdogTimer: Timer = Timer()

    private var backgroundTask:  BackgroundTaskController? = null
    private var signalingClient: HASignalingClient? = null
    private var signalingRetryHandler: Handler? = null
    private var signalingRetryRunnable: Runnable? = null
    private var signalingHealthTimer: Timer? = null
    @Volatile private var signalingConnected = false
    @Volatile private var signalingManuallyStopped = false

    private fun shouldRunSignaling(): Boolean {
        return config.pairedDeviceID.isNotBlank() && config.accessToken.isNotBlank()
    }

    private val signalingEventListener = object : EventListener {
        override fun onEventTriggered(event: Event) {
            when (event.eventName) {
                "pairedDeviceID" -> {
                    val paired = event.newValue as? String ?: ""
                    if (paired.isNotBlank()) {
                        signalingManuallyStopped = false
                        startSignalingClient()
                    } else {
                        stopSignalingClient(manualStop = true)
                    }
                }
                "accessToken" -> {
                    val token = event.newValue as? String ?: ""
                    if (token.isNotBlank()) {
                        signalingManuallyStopped = false
                        // Token changed — tear down existing client and reconnect
                        // with the new token so auth stays valid.
                        stopSignalingClient(manualStop = false)
                        startSignalingClient()
                    } else {
                        stopSignalingClient(manualStop = true)
                    }
                }
            }
        }
    }

    enum class Actions {
        START, STOP
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        config = APPConfig.getInstance(this)
        config.eventBroadcaster.addListener(signalingEventListener)

        // wifi lock
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "vacompanion.VABackgroundService:wifiLock")
        // Some Amazon devices are not seeing this permission so we are trying to check
        val permission = "android.permission.DISABLE_KEYGUARD"
        val checkSelfPermission = ContextCompat.checkSelfPermission(this@VAForegroundService, permission)
        if (checkSelfPermission == PackageManager.PERMISSION_GRANTED) {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardLock = keyguardManager.newKeyguardLock("ALARM_KEYBOARD_LOCK_TAG")
            keyguardLock!!.disableKeyguard()
        }
    }

    /**
    * Main process for the service
    * */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var action = intent?.action ?: Actions.START.toString()
        Timber.v("onStartCommand action: $action")
        if (intent == null) {
            Timber.v("VACA restarted by OS after crash")
            startActivity(this)
            action = Actions.START.toString()
        }
        // Do the work that the service needs to do here
        when (action) {
            Actions.START.toString() -> {
                Firebase.crashlytics.log("Background service starting")
                if (!checkIfPermissionIsGranted()) return START_STICKY

                //need core 1.12 and higher and SDK 30 and higher
                var requires: Int = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requires += ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requires += ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    requires += ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }

                val notification =
                    NotificationCompat.Builder(this, "VACAForegroundServiceChannelId")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("View Assist Companion App")
                        .setContentText("Service is running")
                        .addAction(
                           R.drawable.outline_stop_circle_24, getString(R.string.stop_service),
                            stopServiceIntent(Actions.STOP.toString())
                        )
                        .build()

                Timber.d("Running in foreground ServiceCompat mode")
                ServiceCompat.startForeground(
                    this@VAForegroundService,
                    1,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        requires
                    } else {
                        0
                    },
                )

                if (!wifiLock!!.isHeld) {
                    wifiLock!!.acquire()
                }
                try {
                    keyguardLock?.disableKeyguard()
                } catch (ex: Exception) {
                    Timber.i("Disabling keyguard didn't work")
                    ex.printStackTrace()
                    Firebase.crashlytics.recordException(ex)
                }
                backgroundTask = BackgroundTaskController(this)
                backgroundTask?.start()
                startSignalingClient()
                Timber.i("Background Service Started")
                config.backgroundTaskRunning = true
                config.backgroundTaskStatus = BackgroundTaskStatus.STARTED

                // Launch Activity if not running on service start
                // Can be caused by crash and service restarted by OS
                if (config.currentActivity == "") {
                    Timber.i("Launching MainActivity from foreground service")
                    Firebase.crashlytics.log("Launching MainActivity from foreground service")
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    try {
                        startActivity(intent)
                    } catch (ex: Exception) {
                        Timber.e("Foreground service failed to launch activity - ${ex.message}")
                    }
                }
                restartActivityWatchdog()
            }
            Actions.STOP.toString() -> {
                Firebase.crashlytics.log("Background service stopping")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startActivity(context: Context) {
        try {
            val myIntent = Intent(context, MainActivity::class.java)
            myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(myIntent)
        } catch (ex: Exception) {
            Timber.e("Watchdog failed to restart activity - ${ex.message}")
        }
    }

    private fun restartActivityWatchdog() {
        watchdogTimer.schedule(object: TimerTask() {
            override fun run() {
                if (VACAApplication.activityManager.activity == null) {
                    Timber.d("Watchdog detected activity not running.  Restarting...")
                    startActivity(this@VAForegroundService)
                }
            }
        },0,5000)
    }

    private fun stopServiceIntent(name: String): PendingIntent {
        val intent = Intent(this, VAForegroundService::class.java)
        intent.setAction(name)
        val pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return pendingIntent
    }

    private fun checkIfPermissionIsGranted() = ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        Timber.i("Stopping Background Service")
        watchdogTimer.cancel()
        stopSignalingClient(manualStop = true)
        config.eventBroadcaster.removeListener(signalingEventListener)
        backgroundTask?.shutdown()
        config.backgroundTaskRunning = false
        config.backgroundTaskStatus = BackgroundTaskStatus.NOT_STARTED

        // Release any lock from this app
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
        try {
            keyguardLock!!.reenableKeyguard()
        } catch (ex: Exception) {
            Timber.i("Enabling keyguard didn't work")
            ex.printStackTrace()
            Firebase.crashlytics.recordException(ex)
        }
    }

    private fun startSignalingClient() {
        if (signalingManuallyStopped) {
            Timber.d("Signaling client start skipped: manually stopped")
            return
        }

        // Cancel any pending retry first to avoid races where both the retry
        // and a manual start fire concurrently.
        if (signalingRetryHandler != null && signalingRetryRunnable != null) {
            signalingRetryHandler?.removeCallbacks(signalingRetryRunnable!!)
            signalingRetryHandler = null
            signalingRetryRunnable = null
        }
        if (signalingClient != null) return
        if (!shouldRunSignaling()) {
            Timber.d("Signaling client not started: pairing or token missing")
            if (DEBUG_OVERLAY) showDebugToast("Signaling: missing access token — retrying")
            return
        }

        // Resolve base URL and ensure it contains a host (not just a port)
        val base = com.msp1974.vacompanion.utils.AuthUtils.getHAUrl(config, withDashboardPath = false)
        val wsUrl = base.removePrefix("http://").removePrefix("https://")
        if (wsUrl.isBlank() || wsUrl.startsWith(":") || wsUrl.matches(Regex("^\\d+$"))) {
            Timber.d("Signaling client not started: no valid HA host available (base='$base')")
            if (DEBUG_OVERLAY) showDebugToast("Signaling waiting for HA host: $base")
            if (signalingRetryHandler == null && signalingRetryRunnable == null) {
                signalingRetryHandler = Handler(Looper.getMainLooper())
                signalingRetryRunnable = Runnable {
                    signalingRetryHandler = null
                    signalingRetryRunnable = null
                    startSignalingClient()
                }
                signalingRetryHandler?.postDelayed(signalingRetryRunnable!!, 5000)
            }
            return
        }

        signalingClient = HASignalingClient(config, object : HASignalingListener {
            override fun onOfferReceived(data: JSONObject) {
                try {
                    val target = data.getString("target_device")
                    val caller = data.getString("caller_uuid")
                    val sdp = data.getString("sdp")

                    if (DEBUG_OVERLAY) {
                        try { config.eventBroadcaster.notifyEvent(Event("signalingLog", "", "offer from $caller to $target")) } catch (e: Exception) {}
                    }

                    if (target != config.uuid) return

                    // If already in a call, send busy signal back
                    if (isVideoCallActivityActive()) {
                        Timber.d("Already in a call, sending busy to $caller")
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                signalingClient?.sendCallBusy(config.uuid, caller)
                            } catch (_: Exception) {}
                        }
                        return
                    }

                    if (DEBUG_OVERLAY) showDebugToast("Incoming call offer from $caller")

                    // Show in-app overlay instead of notification
                    val payload = JSONObject().apply {
                        put("caller_uuid", caller)
                        put("sdp", sdp)
                    }
                    config.eventBroadcaster.notifyEvent(Event("incomingCall", "", payload.toString()))
                } catch (e: Exception) {
                    Logger().e("Foreground signaling offer error: $e")
                }
            }

            override fun onAnswerReceived(data: JSONObject) {
                try {
                    val caller = data.getString("caller_uuid")
                    val target = data.getString("target_device")
                    if (DEBUG_OVERLAY) {
                        try { config.eventBroadcaster.notifyEvent(Event("signalingLog", "", "answer from $caller to $target")) } catch (e: Exception) {}
                    }
                    // Route to VideoCallActivity via event bus
                    if (target == config.uuid) {
                        config.eventBroadcaster.notifyEvent(Event("webrtcAnswer", "", data.toString()))
                    }
                } catch (e: Exception) {}
            }

            override fun onIceReceived(data: JSONObject) {
                try {
                    val caller = data.getString("caller_uuid")
                    val target = data.getString("target_device")
                    if (DEBUG_OVERLAY) {
                        try { config.eventBroadcaster.notifyEvent(Event("signalingLog", "", "ice from $caller to $target")) } catch (e: Exception) {}
                    }
                    // Route to VideoCallActivity via event bus
                    if (target == config.uuid) {
                        config.eventBroadcaster.notifyEvent(Event("webrtcIce", "", data.toString()))
                    }
                } catch (e: Exception) {}
            }

            override fun onStartCallReceived(data: JSONObject) {
                try {
                    val caller = data.getString("caller_uuid")
                    val target = data.getString("target_device")

                    if (DEBUG_OVERLAY) {
                        try { config.eventBroadcaster.notifyEvent(Event("signalingLog", "", "start_call from $caller to $target")) } catch (e: Exception) {}
                        showDebugToast("start_call from $caller to $target")
                    }


                    if (caller == config.uuid && target.isNotBlank()) {
                        val intent = Intent(this@VAForegroundService, VideoCallActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("target_device", target)
                        }
                        startActivity(intent)
                    }
                } catch (e: Exception) {
                    Logger().e("Foreground signaling start-call error: $e")
                }
            }

            override fun onCallEndedReceived(data: JSONObject) {
                try {
                    val caller = data.optString("caller_uuid", "")
                    val target = data.optString("target_device", "")
                    if (DEBUG_OVERLAY) {
                        try { config.eventBroadcaster.notifyEvent(Event("signalingLog", "", "call_ended from $caller to $target")) } catch (e: Exception) {}
                    }
                    // If this device is either the caller or target, end the call
                    if (target == config.uuid || caller == config.uuid) {
                        config.eventBroadcaster.notifyEvent(Event("callEnded", "", ""))
                    }
                } catch (e: Exception) {
                    Logger().e("Foreground signaling call-ended error: $e")
                }
            }

            override fun onCallDeclinedReceived(data: JSONObject) {
                try {
                    val caller = data.optString("caller_uuid", "")
                    val target = data.optString("target_device", "")
                    if (DEBUG_OVERLAY) {
                        try { config.eventBroadcaster.notifyEvent(Event("signalingLog", "", "call_declined by $target for $caller")) } catch (e: Exception) {}
                    }
                    // If this device is the caller, end the call (callee declined)
                    if (caller == config.uuid) {
                        config.eventBroadcaster.notifyEvent(Event("callEnded", "", ""))
                    }
                } catch (e: Exception) {
                    Logger().e("Foreground signaling call-declined error: $e")
                }
            }

            override fun onCallRingingReceived(data: JSONObject) {
                try {
                    val caller = data.optString("caller_uuid", "")
                    val target = data.optString("target_device", "")
                    if (DEBUG_OVERLAY) {
                        try { config.eventBroadcaster.notifyEvent(Event("signalingLog", "", "call_ringing from $target for $caller")) } catch (e: Exception) {}
                    }
                    // If this device is the caller, notify UI that the target is ringing
                    if (caller == config.uuid) {
                        config.eventBroadcaster.notifyEvent(Event("callRinging", "", target))
                    }
                } catch (e: Exception) {
                    Logger().e("Foreground signaling call-ringing error: $e")
                }
            }

            override fun onCallBusyReceived(data: JSONObject) {
                try {
                    val caller = data.optString("caller_uuid", "")
                    val target = data.optString("target_device", "")
                    if (DEBUG_OVERLAY) {
                        try { config.eventBroadcaster.notifyEvent(Event("signalingLog", "", "call_busy from $target for $caller")) } catch (e: Exception) {}
                    }
                    // If this device is the caller, notify that the target is busy
                    if (caller == config.uuid) {
                        config.eventBroadcaster.notifyEvent(Event("callBusy", "", target))
                    }
                } catch (e: Exception) {
                    Logger().e("Foreground signaling call-busy error: $e")
                }
            }
        })

        signalingClient?.statusCallback = { connected ->
            Timber.d("Signaling status changed: $connected")
            signalingConnected = connected
            if (DEBUG_OVERLAY) showDebugToast("Signaling: ${if (connected) "Connected" else "Disconnected"}")
            config.eventBroadcaster.notifyEvent(Event("signalingConnected", "", connected))
            if (!connected) {
                // Auto-reconnect after a delay
                scheduleSignalingReconnect()
            }
        }

        signalingClient?.logCallback = { msg ->
            try {
                if (DEBUG_OVERLAY) config.eventBroadcaster.notifyEvent(Event("signalingLog", "", msg))
                Timber.d("SIGLOG: $msg")
            } catch (e: Exception) {}
        }

        signalingClient?.connect()
        Timber.d("Foreground signaling client started")
        startSignalingHealthCheck()
    }

    private fun stopSignalingClient(manualStop: Boolean = false) {
        if (manualStop) {
            signalingManuallyStopped = true
        }
        // cancel health check
        signalingHealthTimer?.cancel()
        signalingHealthTimer = null
        // cancel any pending retry
        if (signalingRetryHandler != null && signalingRetryRunnable != null) {
            signalingRetryHandler?.removeCallbacks(signalingRetryRunnable!!)
            signalingRetryHandler = null
            signalingRetryRunnable = null
        }
        signalingClient?.close()
        signalingClient = null
        signalingConnected = false
        Timber.d("Foreground signaling client stopped")
    }

    /**
     * Schedule a reconnect attempt after a delay.
     * Cleans up the current client first so startSignalingClient() can create a new one.
     */
    private fun scheduleSignalingReconnect(delayMs: Long = 10_000) {
        if (signalingManuallyStopped || !shouldRunSignaling()) {
            Timber.d("Skipping signaling reconnect: manual stop or missing prerequisites")
            return
        }

        // Avoid duplicate scheduled reconnects
        if (signalingRetryHandler != null && signalingRetryRunnable != null) return

        Timber.d("Scheduling signaling reconnect in ${delayMs}ms")
        signalingClient?.close()
        signalingClient = null

        signalingRetryHandler = Handler(Looper.getMainLooper())
        signalingRetryRunnable = Runnable {
            signalingRetryHandler = null
            signalingRetryRunnable = null
            startSignalingClient()
        }
        signalingRetryHandler?.postDelayed(signalingRetryRunnable!!, delayMs)
    }

    /**
     * Periodic health check: if the signaling WebSocket has been disconnected
     * for too long with no pending reconnect, force a reconnect.
     * Defense-in-depth against edge cases where statusCallback(false) is missed.
     */
    private fun startSignalingHealthCheck() {
        signalingHealthTimer?.cancel()
        signalingHealthTimer = Timer()
        signalingHealthTimer?.schedule(object : TimerTask() {
            override fun run() {
                // Case 1: No client and no retry pending — orphaned state
                if (signalingClient == null
                    && signalingRetryHandler == null
                    && !signalingManuallyStopped
                    && shouldRunSignaling()) {
                    Timber.w("Signaling health check: no client, no retry pending — forcing reconnect")
                    Handler(Looper.getMainLooper()).post {
                        startSignalingClient()
                    }
                    return
                }
                // Case 2: Client exists but reports disconnected (zombie) — tear down and reconnect
                val client = signalingClient
                if (client != null && !client.isConnected
                    && signalingRetryHandler == null
                    && !signalingManuallyStopped
                    && shouldRunSignaling()) {
                    Timber.w("Signaling health check: zombie connection detected — forcing reconnect")
                    Handler(Looper.getMainLooper()).post {
                        stopSignalingClient(manualStop = false)
                        startSignalingClient()
                    }
                }
            }
        }, 60_000, 60_000)  // check every 60 seconds
    }

    private fun isVideoCallActivityActive(): Boolean {
        val activity = VACAApplication.activityManager.activity
        return activity is VideoCallActivity
    }

    private fun showDebugToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(this@VAForegroundService, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Timber.d("Debug toast failed: ${e.message}")
            }
        }
    }

}
