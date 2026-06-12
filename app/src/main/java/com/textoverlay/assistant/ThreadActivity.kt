package com.textoverlay.assistant

import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.textoverlay.assistant.databinding.ActivityThreadBinding
import kotlinx.coroutines.launch

/**
 * A single conversation: shows the history, the Claude actions, and a compose
 * box. "Send" hands the message to the phone's default Messages app (prefilled),
 * so the app never needs the SMS-send permission that Samsung blocks.
 */
class ThreadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThreadBinding
    private lateinit var repo: SmsRepository
    private lateinit var claude: ClaudeClient
    private val adapter = MessageAdapter()

    private var threadId: Long = -1L
    private var address: String? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = SmsRepository(this)
        claude = ClaudeClient(SettingsStore(this))

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.list.adapter = adapter

        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        address = intent.getStringExtra(EXTRA_ADDRESS)
        val newConversation = threadId <= 0 && address.isNullOrBlank()
        binding.recipient.visibility = if (newConversation) View.VISIBLE else View.GONE
        title = if (address.isNullOrBlank()) getString(R.string.new_message)
        else repo.displayName(address!!)

        binding.sendButton.setOnClickListener { send() }
        binding.summarizeButton.setOnClickListener { runClaude(fillReply = false) }
        binding.suggestButton.setOnClickListener { runClaude(fillReply = true) }
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        reload()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
    }

    private fun reload() {
        if (threadId <= 0) return
        lifecycleScope.launch {
            val msgs = repo.loadMessages(threadId)
            adapter.submit(msgs)
            if (msgs.isNotEmpty()) binding.list.scrollToPosition(msgs.size - 1)
        }
    }

    private fun currentAddress(): String? {
        val typed = binding.recipient.text?.toString()?.trim()
        return if (!typed.isNullOrBlank()) typed else address
    }

    /** Hand the message to the default Messages app, prefilled and ready to send. */
    private fun send() {
        val body = binding.input.text?.toString()?.trim().orEmpty()
        val addr = currentAddress()
        if (addr.isNullOrBlank()) {
            toast(getString(R.string.enter_recipient))
            return
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(addr)))
            .putExtra("sms_body", body)
        // Route straight to the system default Messages app when we know it.
        Telephony.Sms.getDefaultSmsPackage(this)?.let { intent.setPackage(it) }
        runCatching { startActivity(intent) }
            .onSuccess { binding.input.setText("") }
            .onFailure {
                // Fall back to letting the user pick an app.
                runCatching {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(addr)))
                        .putExtra("sms_body", body))
                }.onFailure { toast(getString(R.string.no_messaging_app)) }
            }
    }

    private fun runClaude(fillReply: Boolean) {
        if (!SettingsStore(this).hasApiKey) {
            toast(getString(R.string.no_api_key_short))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val transcript = buildTranscript()
            if (transcript.isBlank()) {
                setBusy(false)
                toast(getString(R.string.no_history))
                return@launch
            }
            val name = currentAddress()?.let { repo.displayName(it) } ?: "them"
            runCatching { claude.analyze(name, transcript) }
                .onSuccess { suggestion ->
                    setBusy(false)
                    if (fillReply) {
                        binding.input.setText(suggestion.suggestedReply)
                        binding.input.setSelection(binding.input.text.length)
                    } else {
                        showSummary(suggestion.summary)
                    }
                }
                .onFailure {
                    setBusy(false)
                    toast(it.message ?: "Couldn't reach Claude.")
                }
        }
    }

    private suspend fun buildTranscript(): String {
        if (threadId <= 0) return ""
        val msgs = repo.loadMessages(threadId)
        return msgs.joinToString("\n") { (if (it.incoming) "Them: " else "Me: ") + it.body }
    }

    private fun showSummary(summary: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.summary_title)
            .setMessage(summary)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.summarizeButton.isEnabled = !busy
        binding.suggestButton.isEnabled = !busy
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_ADDRESS = "address"
    }
}
