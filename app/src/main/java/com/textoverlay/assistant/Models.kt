package com.textoverlay.assistant

/** One SMS conversation (thread), summarized for the list screen. */
data class Conversation(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val snippet: String,
    val date: Long,
    val unread: Boolean
)

/** A single message within a thread (SMS text, or an MMS with an image). */
data class SmsMessage(
    val body: String,
    val date: Long,
    /** true if received, false if sent by me. */
    val incoming: Boolean,
    /** content:// uri of an attached image (MMS), if any. */
    val imageUri: String? = null,
    /** MIME type of the attached image, e.g. "image/jpeg". */
    val imageType: String? = null
)
