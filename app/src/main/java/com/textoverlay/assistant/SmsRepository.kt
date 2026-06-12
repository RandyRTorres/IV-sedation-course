package com.textoverlay.assistant

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads SMS conversations from the system Telephony provider (best-effort —
 * works only if READ_SMS is granted) and resolves contact names. Sending is
 * handled by handing the message off to the phone's default Messages app, so
 * this class never needs send/write permissions.
 */
class SmsRepository(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val nameCache = HashMap<String, String>()

    /** All SMS threads, newest first, each represented by its latest message. */
    suspend fun loadConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val seen = HashSet<Long>()
        val out = ArrayList<Conversation>()
        val projection = arrayOf(
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE
        )
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            val threadIdx = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIdx = c.getColumnIndexOrThrow(Telephony.Sms.READ)
            val typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (c.moveToNext()) {
                val threadId = c.getLong(threadIdx)
                if (!seen.add(threadId)) continue // already captured the latest row
                val address = c.getString(addrIdx).orEmpty()
                val incoming = c.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX
                out += Conversation(
                    threadId = threadId,
                    address = address,
                    displayName = displayName(address),
                    snippet = c.getString(bodyIdx).orEmpty(),
                    date = c.getLong(dateIdx),
                    unread = incoming && c.getInt(readIdx) == 0
                )
            }
        }
        out
    }

    /** All messages in a thread, oldest first. */
    suspend fun loadMessages(threadId: Long): List<SmsMessage> = withContext(Dispatchers.IO) {
        val out = ArrayList<SmsMessage>()
        val projection = arrayOf(
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (c.moveToNext()) {
                out += SmsMessage(
                    body = c.getString(bodyIdx).orEmpty(),
                    date = c.getLong(dateIdx),
                    incoming = c.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX
                )
            }
        }
        out
    }

    /** Map a phone number to a contact name, falling back to the number itself. */
    fun displayName(address: String): String {
        if (address.isBlank()) return "(unknown)"
        nameCache[address]?.let { return it }
        var name = address
        runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            resolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) name = n
                }
            }
        }
        nameCache[address] = name
        return name
    }
}
