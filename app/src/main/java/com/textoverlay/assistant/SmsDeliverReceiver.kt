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
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives incoming SMS while this app is the default SMS app. Writes the
 * message to the inbox and posts a notification — summarized by Claude for
 * longer messages — with an inline reply action.
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

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                notify(context, sender, body, previewFor(context, sender, body))
            } finally {
                pending.finish()
            }
        }
    }

    /** Raw body for short texts; a one-line Claude summary for longer ones. */
    private suspend fun previewFor(context: Context, sender: String, body: String): String {
        if (body.length <= 160) return body
        val settings = SettingsStore(context)
        if (!settings.hasApiKey) return body
        val name = SmsRepository(context).displayName(sender)
        return withTimeoutOrNull(9000) {
            runCatching { ClaudeClient(settings).summarize(name, "Them: $body") }.getOrNull()
        } ?: body
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

    private fun notify(context: Context, sender: String, body: String, preview: String) {
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
        val notifId = sender.hashCode()
        val displayName = SmsRepository(context).displayName(sender)

        val tap = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, ThreadActivity::class.java)
                .putExtra(ThreadActivity.EXTRA_ADDRESS, sender)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Inline reply
        val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
            action = ReplyReceiver.ACTION_REPLY
            putExtra(ReplyReceiver.EXTRA_ADDRESS, sender)
            putExtra(ReplyReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val replyPending = PendingIntent.getBroadcast(
            context, notifId, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingMutable()
        )
        val remoteInput = RemoteInput.Builder(ReplyReceiver.KEY_REPLY)
            .setLabel(context.getString(R.string.reply))
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_send_arrow, context.getString(R.string.reply), replyPending
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()

        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setConversationTitle(displayName)
            .addMessage(preview, System.currentTimeMillis(), Person.Builder().setName(displayName).build())

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle(displayName)
            .setContentText(preview)
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .addAction(replyAction)
            .build()
        runCatching { nm.notify(notifId, notification) }
    }

    private fun pendingMutable(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private companion object {
        const val CHANNEL_ID = "incoming_messages"
    }
}
