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
    private val settings by lazy { SettingsStore(this) }
    private var showingArchived = false
    private val adapter = ConversationAdapter(
        onClick = { openThread(it.threadId, it.address) },
        onLongClick = { showConversationMenu(it) }
    )

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
        binding.toolbar.subtitle = "v${BuildConfig.VERSION_NAME}"
        binding.toolbar.setSubtitleTextColor(0xFF8E8E93.toInt())
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
            val archived = settings.archivedThreads
            val items = repo.loadConversations().filter {
                if (showingArchived) it.threadId in archived else it.threadId !in archived
            }
            adapter.submit(items)
            binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Long-press a conversation → archive/unarchive or delete it. */
    private fun showConversationMenu(conv: Conversation) {
        val archiveLabel = if (showingArchived) R.string.unarchive else R.string.archive
        AlertDialog.Builder(this)
            .setTitle(conv.displayName)
            .setItems(arrayOf(getString(archiveLabel), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> {
                        if (showingArchived) settings.unarchive(conv.threadId)
                        else settings.archive(conv.threadId)
                        refresh()
                    }
                    1 -> confirmDelete(conv)
                }
            }
            .show()
    }

    private fun confirmDelete(conv: Conversation) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_thread_title)
            .setMessage(getString(R.string.delete_thread_body, conv.displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    repo.deleteThread(conv.threadId)
                    settings.unarchive(conv.threadId)
                    refresh()
                }
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        menu.findItem(R.id.action_archived)?.setTitle(
            if (showingArchived) R.string.show_all else R.string.archived
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_archived) {
            showingArchived = !showingArchived
            binding.toolbar.title = getString(if (showingArchived) R.string.archived else R.string.conversations)
            invalidateOptionsMenu()
            refresh()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun openThread(threadId: Long, address: String?) {
        startActivity(
            Intent(this, ThreadActivity::class.java)
                .putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                .putExtra(ThreadActivity.EXTRA_ADDRESS, address)
        )
    }
}
