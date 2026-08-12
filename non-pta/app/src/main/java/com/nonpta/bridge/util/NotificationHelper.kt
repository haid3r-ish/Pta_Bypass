package com.nonpta.bridge.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.nonpta.bridge.MainActivity
import com.nonpta.bridge.R
import com.nonpta.bridge.receiver.CallActionReceiver

object NotificationHelper {
    private const val CALL_CHANNEL_ID = "bridge_call_channel"
    private const val SMS_CHANNEL_ID = "bridge_sms_channel"
    const val CALL_NOTIFICATION_ID = 2001
    const val SMS_NOTIFICATION_ID = 2002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming calls"
            }
            nm.createNotificationChannel(callChannel)

            val smsChannel = NotificationChannel(
                SMS_CHANNEL_ID,
                "Incoming Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new messages"
            }
            nm.createNotificationChannel(smsChannel)
        }
    }

    fun showIncomingCallNotification(context: Context, callerName: String, callerNumber: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create a Person representing the caller
        val caller = Person.Builder()
            .setName(callerName)
            .setUri("tel:$callerNumber")
            .build()

        // Action Intents
        val answerIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_ANSWER
        }
        val answerPendingIntent = PendingIntent.getBroadcast(
            context, 1, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context, 2, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Full Screen Intent (Wakes device from lock screen)
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 3, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Placeholder until specific icons are generated
            .setContentTitle("Incoming Call")
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)

        // Android 12+ (API 31+) native CallStyle wrapper
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val style = NotificationCompat.CallStyle.forIncomingCall(caller, declinePendingIntent, answerPendingIntent)
            builder.setStyle(style)
        } else {
            // Fallback generic actions for old APIs
            builder.addAction(R.drawable.ic_launcher_foreground, "Decline", declinePendingIntent)
            builder.addAction(R.drawable.ic_launcher_foreground, "Answer", answerPendingIntent)
        }

        nm.notify(CALL_NOTIFICATION_ID, builder.build())
    }

    fun showMessageNotification(context: Context, sender: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_chat", sender)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 4, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, SMS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("New message from $sender")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        nm.notify(SMS_NOTIFICATION_ID, builder.build())
    }

    fun cancelCallNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(CALL_NOTIFICATION_ID)
    }
}
