package com.textoverlay.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat

/**
 * Receives incoming SMS while this app is the default SMS app. The default app
 * is responsible for writing received messages into the provider's inbox and
 * for notifying the user, so we do both here.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Multipart messages arrive as several parts of the same SMS — join them.
        val sender = messages[0].displayOriginatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val timestamp = messages[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

        writeToInbox(context, sender, body, timestamp)
        notify(context, sender, body)
    }

    private fun writeToInbox(context: Context, sender: String, body: String, timestamp: Long) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sender)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        runCatching { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }
    }

    private fun notify(context: Context, sender: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_messages),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val displayName = SmsRepository(context).displayName(sender)
        val tap = PendingIntent.getActivity(
            context,
            sender.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle(displayName)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { nm.notify(sender.hashCode(), notification) }
    }

    private companion object {
        const val CHANNEL_ID = "incoming_messages"
    }
}
