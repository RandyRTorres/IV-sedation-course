package com.textoverlay.assistant

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and writes SMS through the system Telephony provider, and sends texts
 * over the air. Only meaningful while this app is the default SMS app — that is
 * what grants write access to the provider and the ability to send.
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

    /** Mark every message in a thread as read. */
    suspend fun markThreadRead(threadId: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
        runCatching {
            resolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString())
            )
        }
        Unit
    }

    /** Resolve (or create) the thread id for an address. */
    suspend fun threadIdFor(address: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            Telephony.Threads.getOrCreateThreadId(context, address)
        }.getOrDefault(0L)
    }

    /**
     * Send a text and record it in the provider's Sent box (the default app is
     * responsible for persisting its own sent messages).
     */
    suspend fun sendMessage(address: String, body: String) = withContext(Dispatchers.IO) {
        val sms = smsManager()
        val parts = sms.divideMessage(body)
        if (parts.size > 1) {
            sms.sendMultipartTextMessage(address, null, parts, null, null)
        } else {
            sms.sendTextMessage(address, null, body, null, null)
        }
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
        }
        runCatching { resolver.insert(Telephony.Sms.Sent.CONTENT_URI, values) }
        Unit
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun smsManager(): SmsManager =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
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
