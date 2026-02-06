package com.msp1974.vacompanion.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.msp1974.vacompanion.utils.Logger

object CallNotifications {
    private const val CHANNEL_ID = "vaca_incoming_calls"

    fun showIncomingCall(caller: String, sdp: String, context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Incoming Calls",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                    channel.description = "Incoming video calls"
                    channel.vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                    nm.createNotificationChannel(channel)
                }

                val acceptIntent = Intent(context, CallActionReceiver::class.java).apply {
                    action = CallActionReceiver.ACTION_ACCEPT
                    putExtra(CallActionReceiver.EXTRA_CALLER, caller)
                    putExtra(CallActionReceiver.EXTRA_SDP, sdp)
                }
                val acceptPI = PendingIntent.getBroadcast(
                    context,
                    caller.hashCode(),
                    acceptIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
                    action = CallActionReceiver.ACTION_DECLINE
                    putExtra(CallActionReceiver.EXTRA_CALLER, caller)
                }
                val declinePI = PendingIntent.getBroadcast(
                    context,
                    caller.hashCode().inv(),
                    declineIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_call_incoming)
                    .setContentTitle("Incoming call")
                    .setContentText("From $caller")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setSound(ringtone)
                    .addAction(android.R.drawable.sym_call_incoming, "Accept", acceptPI)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePI)
                    .setAutoCancel(true)
                    .build()

                val notifId = 1000 + (caller.hashCode() % 1000)
                NotificationManagerCompat.from(context).notify(notifId, notification)

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    NotificationManagerCompat.from(context).cancel(notifId)
                }, 30_000)
            }
        } catch (e: Exception) {
            Logger().e("Error showing incoming notification: $e")
        }
    }
}
