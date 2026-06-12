package com.textoverlay.assistant

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager

/**
 * Handles "Respond via message" requests (e.g. declining a call with a text).
 * Declaring this service is required for default-SMS-app eligibility.
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_RESPOND_VIA_MESSAGE) {
            val recipients = intent.data?.schemeSpecificPart
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (recipients.isNotEmpty() && text.isNotEmpty()) {
                val sms = runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                        getSystemService(SmsManager::class.java)
                    else @Suppress("DEPRECATION") SmsManager.getDefault()
                }.getOrNull()
                recipients.forEach { runCatching { sms?.sendTextMessage(it, null, text, null, null) } }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
