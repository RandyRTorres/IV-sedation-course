package com.textoverlay.assistant

import android.content.Context
import android.telephony.SmsManager
import java.io.File

/** Sends a picture/GIF message as MMS via the platform MMS service. */
object MmsSender {

    /** Returns true if the send request was handed off successfully. */
    fun send(context: Context, to: String, text: String?, image: ByteArray, imageMime: String): Boolean {
        return runCatching {
            val pdu = MmsPdu.buildSendReq(to, text, image, imageMime)
            val fileName = "mms_${System.currentTimeMillis()}.pdu"
            File(context.cacheDir, fileName).writeBytes(pdu)
            val contentUri = MmsFileProvider.uriFor(fileName)
            smsManager(context).sendMultimediaMessage(context, contentUri, null, null, null)
            true
        }.getOrDefault(false)
    }

    private fun smsManager(context: Context): SmsManager =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
}
