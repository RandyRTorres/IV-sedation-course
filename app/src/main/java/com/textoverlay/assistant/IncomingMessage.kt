package com.textoverlay.assistant

/**
 * A single incoming message captured from another app's notification.
 *
 * @param appName    Human-readable name of the source app (e.g. "Messages", "WhatsApp").
 * @param packageName The source app's package id.
 * @param sender     Best guess at who sent it (notification title).
 * @param text       The message body.
 * @param timestamp  When the notification was posted (epoch millis).
 */
data class IncomingMessage(
    val appName: String,
    val packageName: String,
    val sender: String,
    val text: String,
    val timestamp: Long
)
