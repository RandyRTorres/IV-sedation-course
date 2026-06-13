package com.textoverlay.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles inline "Reply" from a message notification: sends the SMS and
 *  updates the notification to confirm. */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        if (text.isNullOrBlank() || address.isNullOrBlank()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = SmsRepository(context)
                runCatching { repo.sendMessage(address, text) }
                val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
                    .setConversationTitle(repo.displayName(address))
                    .addMessage(text, System.currentTimeMillis(), null as Person?)
                val n = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_sms)
                    .setStyle(style)
                    .setContentText(context.getString(R.string.sent))
                    .setAutoCancel(true)
                    .build()
                runCatching { NotificationManagerCompat.from(context).notify(notifId, n) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.textoverlay.assistant.ACTION_REPLY"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val KEY_REPLY = "key_reply"
        const val CHANNEL_ID = "incoming_messages"
    }
}
