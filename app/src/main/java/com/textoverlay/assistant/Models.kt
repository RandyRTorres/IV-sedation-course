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

/** A single message within a thread. */
data class SmsMessage(
    val body: String,
    val date: Long,
    /** true if received, false if sent by me. */
    val incoming: Boolean
)
