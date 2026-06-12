package com.textoverlay.assistant

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.textoverlay.assistant.databinding.ActivityMainBinding

/**
 * One-screen setup: paste your Claude API key, grant the two permissions the
 * overlay needs (draw-over-other-apps + notification access), then start it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)

        binding.apiKeyInput.setText(settings.apiKey)
        binding.toneInput.setText(settings.tone)

        binding.saveButton.setOnClickListener { saveSettings() }
        binding.overlayPermissionButton.setOnClickListener { requestOverlayPermission() }
        binding.notificationAccessButton.setOnClickListener { openNotificationAccess() }
        binding.startButton.setOnClickListener { startOverlay() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun saveSettings() {
        settings.apiKey = binding.apiKeyInput.text?.toString().orEmpty()
        val tone = binding.toneInput.text?.toString().orEmpty()
        settings.tone = tone.ifBlank { SettingsStore.DEFAULT_TONE }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        refreshStatuses()
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun startOverlay() {
        if (!settings.hasApiKey) {
            Toast.makeText(this, R.string.no_api_key, Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        if (!hasNotificationAccess()) {
            Toast.makeText(this, R.string.need_notification_access, Toast.LENGTH_LONG).show()
            openNotificationAccess()
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, R.string.overlay_started, Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    private fun refreshStatuses() {
        binding.overlayStatus.text = statusLine(
            R.string.overlay_permission, Settings.canDrawOverlays(this)
        )
        binding.notificationStatus.text = statusLine(
            R.string.notification_access, hasNotificationAccess()
        )
        binding.apiKeyStatus.text = statusLine(
            R.string.api_key_label, settings.hasApiKey
        )
    }

    private fun statusLine(labelRes: Int, granted: Boolean): String {
        val mark = if (granted) "✓" else "✗"
        return "$mark ${getString(labelRes)}"
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        val component = "$packageName/${MessageNotificationListener::class.java.name}"
        val flat = "$packageName/.${MessageNotificationListener::class.java.simpleName}"
        return enabled.contains(component) || enabled.contains(flat)
    }
}
