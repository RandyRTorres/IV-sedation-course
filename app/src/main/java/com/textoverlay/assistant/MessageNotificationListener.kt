package com.textoverlay.assistant

import android.app.Notification
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Listens to notifications system-wide and forwards anything that looks like an
 * incoming personal message to the overlay. Requires the user to grant
 * "Notification access" (handled from [MainActivity]).
 */
class MessageNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return

        // Never react to our own (or the system's) notifications.
        if (packageName == applicationContext.packageName) return
        if (!looksLikeMessagingApp(packageName)) return

        val n = sbn.notification ?: return

        // Skip ongoing/group-summary/silent noise — we want actual messages.
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = n.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = bestText(extras)
        if (text.isBlank()) return

        val message = IncomingMessage(
            appName = appLabel(packageName),
            packageName = packageName,
            sender = sender.ifBlank { "Someone" },
            text = text,
            timestamp = sbn.postTime
        )

        MessageBus.publish(message)
        ensureOverlayRunning()
    }

    /** Prefer the big/expanded text, fall back to the collapsed line. */
    private fun bestText(extras: android.os.Bundle): String {
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let {
            if (it.isNotBlank()) return it
        }
        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    }

    private fun ensureOverlayRunning() {
        val intent = Intent(this, OverlayService::class.java)
        startForegroundService(intent)
    }

    private fun appLabel(packageName: String): String = try {
        val pm = applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    /**
     * Heuristic allow-list of common messaging apps plus the platform messaging
     * category. Covers SMS, WhatsApp, Messenger, Telegram, Signal, Instagram, etc.
     */
    private fun looksLikeMessagingApp(packageName: String): Boolean {
        if (packageName in KNOWN_MESSAGING_APPS) return true
        // Anything categorized as a messaging/social app by its manifest.
        return try {
            val info = applicationContext.packageManager.getApplicationInfo(packageName, 0)
            info.category == android.content.pm.ApplicationInfo.CATEGORY_SOCIAL
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        private val KNOWN_MESSAGING_APPS = setOf(
            "com.google.android.apps.messaging",   // Google Messages (SMS)
            "com.android.mms",                      // AOSP Messaging
            "com.samsung.android.messaging",        // Samsung Messages
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.facebook.orca",                    // Messenger
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",           // Signal
            "com.instagram.android",
            "com.snapchat.android",
            "com.discord",
            "com.google.android.gm"                 // Gmail
        )
    }
}
