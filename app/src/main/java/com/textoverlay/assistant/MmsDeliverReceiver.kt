package com.textoverlay.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Placeholder MMS receiver. A default SMS app must declare a WAP_PUSH_DELIVER
 * receiver to be eligible for the role; full MMS download/handling is out of
 * scope for this version, so incoming MMS are acknowledged but not processed.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally no-op for now.
    }
}
