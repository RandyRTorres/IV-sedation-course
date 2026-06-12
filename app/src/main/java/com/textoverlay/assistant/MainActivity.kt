package com.textoverlay.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.textoverlay.assistant.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/** Conversation list — the app's home screen. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: SmsRepository
    private val adapter = ConversationAdapter { openThread(it.threadId, it.address) }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private val requestDefault = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = SmsRepository(this)

        setSupportActionBar(binding.toolbar)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.fab.setOnClickListener { openThread(-1L, null) }
        binding.bannerButton.setOnClickListener { fixSetup() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
    }

    private fun isDefaultSmsApp(): Boolean {
        if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) return true
        // getDefaultSmsPackage can lag behind a role granted via adb; trust the role.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                return true
            }
        }
        return false
    }

    private fun hasSmsPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val needed = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val toAsk = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toAsk.isNotEmpty()) requestPermissions.launch(toAsk.toTypedArray())
    }

    /** One button that walks the user through whatever is still missing. */
    private fun fixSetup() {
        if (!hasSmsPermissions()) {
            ensurePermissions()
            return
        }
        if (!isDefaultSmsApp()) requestDefaultSmsApp()
    }

    private fun requestDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                requestDefault.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                return
            }
        }
        @Suppress("DEPRECATION")
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        // Some OEMs ignore the change-default intent; fall back to the system
        // Default apps settings screen so the user can still get there.
        runCatching { requestDefault.launch(intent) }.onFailure {
            runCatching {
                requestDefault.launch(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }
    }

    private fun refresh() {
        val ready = isDefaultSmsApp() && hasSmsPermissions()
        binding.banner.visibility = if (ready) View.GONE else View.VISIBLE
        binding.bannerButton.text = getString(
            if (!hasSmsPermissions()) R.string.grant_permissions else R.string.make_default_button
        )
        if (!hasSmsPermissions()) {
            adapter.submit(emptyList())
            binding.empty.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val items = repo.loadConversations()
            adapter.submit(items)
            binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openThread(threadId: Long, address: String?) {
        startActivity(
            Intent(this, ThreadActivity::class.java)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(ThreadActivity.EXTRA_ADDRESS, address)
        )
    }
}
