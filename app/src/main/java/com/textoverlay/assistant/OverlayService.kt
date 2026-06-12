package com.textoverlay.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground service that owns the floating overlay: a draggable bubble that
 * expands into a panel showing the latest message, an AI summary, and a
 * one-tap-to-copy suggested reply.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var settings: SettingsStore
    private lateinit var claude: ClaudeClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var inflightJob: Job? = null
    private var currentMessage: IncomingMessage? = null
    private var panelOpen = false

    // Views
    private lateinit var bubble: View
    private lateinit var panel: View
    private lateinit var senderLabel: TextView
    private lateinit var originalText: TextView
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var replyText: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        claude = ClaudeClient(settings)
        startForeground(NOTIF_ID, buildForegroundNotification())

        if (!Settings.canDrawOverlays(this)) {
            // No overlay permission — nothing we can draw. Bail cleanly.
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlay()
        observeMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun observeMessages() {
        scope.launch {
            MessageBus.messages.collectLatest { message ->
                currentMessage = message
                bindMessage(message)
                openPanel()
                requestSuggestion(message)
            }
        }
    }

    // ---- UI construction ---------------------------------------------------

    private fun addOverlay() {
        rootView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        bubble = rootView.findViewById(R.id.bubble)
        panel = rootView.findViewById(R.id.panel)
        senderLabel = rootView.findViewById(R.id.sender_label)
        originalText = rootView.findViewById(R.id.original_text)
        statusText = rootView.findViewById(R.id.status_text)
        summaryText = rootView.findViewById(R.id.summary_text)
        replyText = rootView.findViewById(R.id.reply_text)

        rootView.findViewById<Button>(R.id.copy_button).setOnClickListener { copyReply() }
        rootView.findViewById<Button>(R.id.close_button).setOnClickListener { closePanel() }
        rootView.findViewById<Button>(R.id.regenerate_button).setOnClickListener {
            currentMessage?.let { requestSuggestion(it) }
        }

        bubble.setOnClickListener { if (panelOpen) closePanel() else openPanel() }
        attachDragHandler(bubble)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        panel.visibility = View.GONE
        windowManager.addView(rootView, layoutParams)
    }

    private fun attachDragHandler(handle: View) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false

        handle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) dragging = true
                    if (dragging) {
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager.updateViewLayout(rootView, layoutParams)
                    }
                    dragging
                }
                MotionEvent.ACTION_UP -> {
                    // If we didn't drag, let the click listener fire.
                    if (!dragging) v.performClick()
                    dragging
                }
                else -> false
            }
        }
    }

    // ---- Panel state -------------------------------------------------------

    private fun openPanel() {
        panel.visibility = View.VISIBLE
        panelOpen = true
    }

    private fun closePanel() {
        panel.visibility = View.GONE
        panelOpen = false
    }

    private fun bindMessage(message: IncomingMessage) {
        senderLabel.text = getString(R.string.from_via, message.sender, message.appName)
        originalText.text = message.text
        summaryText.text = ""
        replyText.text = ""
        statusText.text = ""
        statusText.visibility = View.GONE
    }

    private fun requestSuggestion(message: IncomingMessage) {
        if (!settings.hasApiKey) {
            showStatus(getString(R.string.no_api_key))
            return
        }
        inflightJob?.cancel()
        showStatus(getString(R.string.thinking))
        summaryText.text = ""
        replyText.text = ""

        inflightJob = scope.launch {
            try {
                val suggestion = claude.summarizeAndSuggest(message)
                statusText.visibility = View.GONE
                summaryText.text = suggestion.summary
                replyText.text = suggestion.suggestedReply
            } catch (e: Exception) {
                showStatus(e.message ?: getString(R.string.generic_error))
            }
        }
    }

    private fun showStatus(text: String) {
        statusText.text = text
        statusText.visibility = View.VISIBLE
    }

    private fun copyReply() {
        val reply = replyText.text?.toString().orEmpty()
        if (reply.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Suggested reply", reply))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ---- Foreground notification ------------------------------------------

    private fun buildForegroundNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_running))
            .setSmallIcon(R.drawable.ic_bubble)
            .setOngoing(true)
            .build()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (::windowManager.isInitialized && ::rootView.isInitialized) {
            runCatching { windowManager.removeView(rootView) }
        }
    }

    companion object {
        private const val CHANNEL_ID = "overlay_service"
        private const val NOTIF_ID = 1001
        private const val TOUCH_SLOP = 12
    }
}
