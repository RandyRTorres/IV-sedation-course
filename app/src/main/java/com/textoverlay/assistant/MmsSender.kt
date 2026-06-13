package com.textoverlay.assistant

import android.app.PendingIntent
import android.content.Context
import android.telephony.SmsManager
import java.io.File

/** Sends a picture/GIF message as MMS via the platform MMS service. */
object MmsSender {

    /**
     * Hands the message off to the platform MMS service. [sentIntent] is
     * broadcast with the result code so the caller can report success/failure.
     * Returns true if the request was dispatched without throwing.
     */
    fun send(
        context: Context,
        to: String,
        text: String?,
        image: ByteArray,
        imageMime: String,
        sentIntent: PendingIntent?
    ): Boolean {
        return runCatching {
            val address = to.filter { it.isDigit() || it == '+' }
            val pdu = MmsPdu.buildSendReq(address, text, image, imageMime)
            val fileName = "mms_${System.currentTimeMillis()}.pdu"
            File(context.cacheDir, fileName).writeBytes(pdu)
            val contentUri = MmsFileProvider.uriFor(fileName)
            smsManager(context).sendMultimediaMessage(context, contentUri, null, null, sentIntent)
            true
        }.getOrDefault(false)
    }

    private fun smsManager(context: Context): SmsManager =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
}
