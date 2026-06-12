package com.textoverlay.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
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
        binding.bannerButton.setOnClickListener { ensurePermissions() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        if (canReadSms()) {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        runCatching { contentResolver.unregisterContentObserver(observer) }
    }

    private fun canReadSms(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val needed = listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
            .filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }

    private fun refresh() {
        // The banner only nudges for read access; sending always works via the
        // phone's Messages app regardless.
        binding.banner.visibility = if (canReadSms()) View.GONE else View.VISIBLE
        binding.bannerButton.text = getString(R.string.grant_permissions)

        if (!canReadSms()) {
            adapter.submit(emptyList())
            binding.empty.visibility = View.VISIBLE
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
