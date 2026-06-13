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

    /**
     * The most recent [limit] messages in a thread (SMS + MMS images), oldest
     * first. Capping keeps long histories fast to open.
     */
    suspend fun loadMessages(threadId: Long, limit: Int = 400): List<SmsMessage> =
        withContext(Dispatchers.IO) {
            val all = ArrayList<SmsMessage>()
            all += loadSms(threadId, limit)
            all += loadMms(threadId, "thread_id = ?", arrayOf(threadId.toString()), limit)
            all.sortBy { it.date }
            // Keep only the newest `limit` after merging SMS + MMS.
            if (all.size > limit) all.subList(0, all.size - limit).clear()
            all
        }

    private fun loadSms(threadId: Long, limit: Int): List<SmsMessage> {
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
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
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
        return out
    }

    /** Read MMS messages in a thread, extracting any text body and image part. */
    private fun loadMms(threadId: Long, selection: String = "thread_id = ?",
                        args: Array<String> = arrayOf(threadId.toString()),
                        limit: Int = 400): List<SmsMessage> {
        val out = ArrayList<SmsMessage>()
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf("_id", "date", "msg_box"),
            selection,
            args,
            "date DESC LIMIT $limit"
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow("_id")
            val dateIdx = c.getColumnIndexOrThrow("date")
            val boxIdx = c.getColumnIndexOrThrow("msg_box")
            while (c.moveToNext()) {
                val mmsId = c.getLong(idIdx)
                // MMS dates are in seconds; SMS dates are in millis.
                val date = c.getLong(dateIdx) * 1000L
                val incoming = c.getInt(boxIdx) == 1 // MESSAGE_BOX_INBOX
                val (text, imageUri, imageType) = mmsParts(mmsId)
                if (text.isNotBlank() || imageUri != null) {
                    out += SmsMessage(text, date, incoming, imageUri, imageType)
                }
            }
        }
        return out
    }

    /** Extract (text, imageUri, imageType) from an MMS message's parts. */
    private fun mmsParts(mmsId: Long): Triple<String, String?, String?> {
        var text = ""
        var imageUri: String? = null
        var imageType: String? = null
        resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "text"),
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        )?.use { p ->
            val pidIdx = p.getColumnIndexOrThrow("_id")
            val ctIdx = p.getColumnIndexOrThrow("ct")
            val textIdx = p.getColumnIndexOrThrow("text")
            while (p.moveToNext()) {
                val ct = p.getString(ctIdx).orEmpty()
                when {
                    ct == "text/plain" -> p.getString(textIdx)?.let { if (it.isNotBlank()) text = it }
                    ct.startsWith("image/") -> {
                        imageUri = "content://mms/part/" + p.getLong(pidIdx)
                        imageType = ct
                    }
                }
            }
        }
        return Triple(text, imageUri, imageType)
    }

    /** Unread incoming messages (SMS + MMS) in a thread, oldest first. */
    suspend fun loadUnread(threadId: Long): List<SmsMessage> = withContext(Dispatchers.IO) {
        val out = ArrayList<SmsMessage>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = ?",
            arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out += SmsMessage(c.getString(0).orEmpty(), c.getLong(1), incoming = true)
            }
        }
        out += loadMms(threadId, "thread_id = ? AND read = 0 AND msg_box = 1",
            arrayOf(threadId.toString()))
        out.sortBy { it.date }
        out
    }

    /**
     * Decode an MMS image part and re-encode it as a clean, modest-sized JPEG
     * for Claude. Returns null if the part can't be decoded (e.g. an MMS that
     * was never downloaded), so callers can simply skip it.
     */
    suspend fun loadImageForClaude(uri: String): ClaudeImage? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                ?: return@runCatching null
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@runCatching null
            val scaled = downscale(bitmap, 1024)
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            ClaudeImage("image/jpeg", out.toByteArray())
        }.getOrNull()
    }

    private fun downscale(bitmap: android.graphics.Bitmap, max: Int): android.graphics.Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= max && h <= max) return bitmap
        val ratio = minOf(max.toFloat() / w, max.toFloat() / h)
        return android.graphics.Bitmap.createScaledBitmap(
            bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true
        )
    }

    /** Delete an entire conversation (SMS + MMS). Requires being the default app. */
    suspend fun deleteThread(threadId: Long) = withContext(Dispatchers.IO) {
        runCatching {
            resolver.delete(Uri.parse("content://mms-sms/conversations/$threadId"), null, null)
        }
        runCatching {
            resolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString())
            )
        }
        runCatching {
            resolver.delete(Telephony.Mms.CONTENT_URI, "thread_id = ?", arrayOf(threadId.toString()))
        }
        Unit
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

    /**
     * Read an attachment to send: GIFs are kept as-is (to preserve animation),
     * other images are re-encoded to a modest JPEG to stay under MMS limits.
     */
    suspend fun readAttachment(uri: Uri): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val mime = resolver.getType(uri) ?: "image/jpeg"
            val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            if (mime == "image/gif") return@runCatching raw to "image/gif"
            if (mime.startsWith("video/")) return@runCatching raw to mime
            val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                ?: return@runCatching raw to mime
            val scaled = downscale(bmp, 1024)
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray() to "image/jpeg"
        }.getOrNull()
    }

    /** Persist attachment bytes to a cache file; returns a uri string for display. */
    fun cacheAttachment(bytes: ByteArray, mime: String): String {
        val ext = when {
            mime == "image/gif" -> "gif"
            mime.startsWith("video/") -> "mp4"
            else -> "jpg"
        }
        val f = java.io.File(context.cacheDir, "sent_${System.currentTimeMillis()}.$ext")
        f.writeBytes(bytes)
        return Uri.fromFile(f).toString()
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
