package com.textoverlay.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import java.io.File
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.textoverlay.assistant.databinding.ActivityThreadBinding
import kotlinx.coroutines.launch

/** A single SMS conversation: messages, compose box, and the Claude actions. */
class ThreadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThreadBinding
    private lateinit var repo: SmsRepository
    private lateinit var claude: ClaudeClient
    private val adapter = MessageAdapter { openImage(it) }

    private var threadId: Long = -1L
    private var address: String? = null
    private var autoSummaryChecked = false

    /** A photo/GIF the user picked but hasn't sent yet. */
    private var pendingAttachment: Uri? = null

    /** Pinch-to-zoom text size for the chat. */
    private var textScale = 1f
    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                textScale = (textScale * d.scaleFactor).coerceIn(0.8f, 2.5f)
                adapter.textScale = textScale
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                SettingsStore(this@ThreadActivity).chatTextScale = textScale
            }
        })
    }

    /** Photo/GIF picker (modern Android photo picker). */
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { setAttachment(it) } }

    /** Where the camera writes a freshly captured photo/video. */
    private var cameraUri: Uri? = null

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) cameraUri?.let { setAttachment(it) } }

    private val captureVideo = registerForActivityResult(
        ActivityResultContracts.CaptureVideo()
    ) { ok -> if (ok) cameraUri?.let { setAttachment(it) } }

    /** Contact picker for sharing a contact into the message body. */
    private val pickContactShare = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            res.data?.data?.let { uri ->
                runCatching {
                    contentResolver.query(
                        uri,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                        ),
                        null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val number = c.getString(0).orEmpty()
                            val name = c.getString(1) ?: number
                            insertAtCursor("$name $number")
                        }
                    }
                }
            }
        }
    }

    /** Messages we've sent this session. Shown even when we can't write them to
     *  the provider (i.e. when we're not the default SMS app). */
    private val locallySent = ArrayList<SmsMessage>()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload()
    }

    /** Reports the result of an MMS send so failures aren't silent. */
    private val mmsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (resultCode == android.app.Activity.RESULT_OK) {
                toast(getString(R.string.sent))
            } else {
                toast(getString(R.string.mms_failed, resultCode))
            }
            reload()
        }
    }

    /** System contact picker (phone-number list). */
    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            res.data?.data?.let { uri ->
                runCatching {
                    contentResolver.query(
                        uri,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                        ),
                        null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val number = c.getString(0).orEmpty()
                            val name = c.getString(1)
                            binding.recipient.setText(number)
                            binding.recipient.dismissDropDown()
                            address = number
                            title = name ?: repo.displayName(number)
                        }
                    }
                }
            }
        }
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

        // Pinch-to-zoom the chat text size (persisted).
        textScale = SettingsStore(this).chatTextScale
        adapter.textScale = textScale
        binding.list.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                scaleDetector.onTouchEvent(e)
                return scaleDetector.isInProgress
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                scaleDetector.onTouchEvent(e)
            }
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })

        resolveTarget()

        binding.sendButton.setOnClickListener { send() }
        binding.summarizeButton.setOnClickListener { summarizeRecent() }
        binding.suggestButton.setOnClickListener { runClaude(fillReply = true) }

        // "To" field: name/number autocomplete + contact picker.
        binding.recipient.setAdapter(ContactCompletionAdapter(this))
        binding.recipient.setOnItemClickListener { _, _, _, _ ->
            binding.recipient.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
                address = it
                title = repo.displayName(it)
            }
        }
        binding.contactsButton.setOnClickListener {
            runCatching {
                pickContact.launch(
                    Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                )
            }
        }

        // Attachments + emoji
        binding.plusButton.setOnClickListener { showAttachMenu() }
        binding.emojiButton.setOnClickListener { showEmojiPicker() }
        binding.attachmentRemove.setOnClickListener { clearAttachment() }

        // Accept images/GIFs inserted from the keyboard (e.g. Gboard GIFs).
        ViewCompat.setOnReceiveContentListener(binding.input, arrayOf("image/*")) { _, payload ->
            val split = payload.partition { it.uri != null }
            split.first?.clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.let { setAttachment(it) }
            split.second
        }

        ContextCompat.registerReceiver(
            this, mmsSentReceiver, IntentFilter(ACTION_MMS_SENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(mmsSentReceiver) }
    }

    private fun showAttachMenu() {
        AlertDialog.Builder(this)
            .setItems(
                arrayOf(
                    getString(R.string.attach_photo),
                    getString(R.string.take_photo),
                    getString(R.string.record_video),
                    getString(R.string.attach_contact)
                )
            ) { _, which ->
                when (which) {
                    0 -> pickMedia.launch(
                        PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            .build()
                    )
                    1 -> launchCamera(video = false)
                    2 -> launchCamera(video = true)
                    3 -> runCatching {
                        pickContactShare.launch(
                            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                        )
                    }
                }
            }
            .show()
    }

    /** Capture a photo or video with the camera app into a shared file. */
    private fun launchCamera(video: Boolean) {
        runCatching {
            val ext = if (video) "mp4" else "jpg"
            val file = File(cacheDir, "cam_${System.currentTimeMillis()}.$ext")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraUri = uri
            if (video) captureVideo.launch(uri) else takePhoto.launch(uri)
        }.onFailure { toast(it.message ?: "Camera unavailable.") }
    }

    private fun setAttachment(uri: Uri) {
        pendingAttachment = uri
        binding.attachmentPreview.visibility = View.VISIBLE
        val type = contentResolver.getType(uri).orEmpty()
        if (type.startsWith("video/")) {
            binding.attachmentThumb.setImageResource(R.drawable.ic_videocam)
        } else {
            runCatching { binding.attachmentThumb.setImageURI(uri) }
        }
    }

    private fun clearAttachment() {
        pendingAttachment = null
        binding.attachmentPreview.visibility = View.GONE
        binding.attachmentThumb.setImageDrawable(null)
    }

    private fun insertAtCursor(text: String) {
        val editable = binding.input.text
        val pos = binding.input.selectionStart.coerceAtLeast(0)
        editable.insert(pos, text)
    }

    private fun showEmojiPicker() {
        val grid = GridView(this).apply {
            numColumns = 6
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(8, 8, 8, 8)
            adapter = object : BaseAdapter() {
                override fun getCount() = EMOJI.size
                override fun getItem(p: Int) = EMOJI[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, cv: View?, parent: android.view.ViewGroup): View {
                    val tv = (cv as? TextView) ?: TextView(this@ThreadActivity).apply {
                        textSize = 26f
                        gravity = Gravity.CENTER
                        setPadding(8, 14, 8, 14)
                    }
                    tv.text = EMOJI[p]
                    return tv
                }
            }
        }
        val popup = PopupWindow(
            grid,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.density * 240).toInt(),
            true
        )
        popup.elevation = 12f
        grid.setOnItemClickListener { _, _, position, _ ->
            insertAtCursor(EMOJI[position])
        }
        popup.showAtLocation(binding.root, Gravity.BOTTOM, 0, 0)
    }

    /** Work out who this conversation is with, from the various launch intents. */
    private fun resolveTarget() {
        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        address = intent.getStringExtra(EXTRA_ADDRESS)

        if (address.isNullOrBlank()) {
            // sms:/smsto: links arrive as data URIs.
            intent.data?.let { uri ->
                if (uri.scheme in setOf("sms", "smsto", "mms", "mmsto")) {
                    address = uri.schemeSpecificPart?.substringBefore('?')
                }
            }
        }
        // A shared/prefilled body, if any.
        intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let {
            if (it.isNotBlank()) binding.input.setText(it)
        }

        val newConversation = threadId <= 0 && address.isNullOrBlank()
        val vis = if (newConversation) View.VISIBLE else View.GONE
        binding.recipientRow.visibility = vis
        binding.recipientDivider.visibility = vis
        title = if (address.isNullOrBlank()) getString(R.string.new_message)
        else repo.displayName(address!!)
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
        val addr = address
        lifecycleScope.launch {
            if (threadId <= 0 && !addr.isNullOrBlank()) {
                threadId = repo.threadIdFor(addr)
            }
            val provider = if (threadId > 0) repo.loadMessages(threadId) else emptyList()
            // Keep any locally-sent message that the provider doesn't already have.
            val extras = locallySent.filter { ls ->
                provider.none { !it.incoming && it.body == ls.body &&
                    kotlin.math.abs(it.date - ls.date) < 60_000 }
            }
            val display = provider + extras
            adapter.submit(display)
            if (display.isNotEmpty()) binding.list.scrollToPosition(display.size - 1)

            // Auto-summarize unread messages once, the first time we open the thread.
            if (!autoSummaryChecked) {
                autoSummaryChecked = true
                val unread = if (threadId > 0) repo.loadUnread(threadId) else emptyList()
                if (unread.isNotEmpty() && SettingsStore(this@ThreadActivity).hasApiKey) {
                    autoSummarize(unread)
                }
            }
            if (threadId > 0) repo.markThreadRead(threadId)
        }
    }

    /** Summarize the unread messages (incl. pictures) into the header bar. */
    private fun autoSummarize(unread: List<SmsMessage>) {
        binding.summaryBar.visibility = View.VISIBLE
        binding.summaryText.text = getString(R.string.summarizing)
        binding.summaryBar.setOnClickListener { binding.summaryBar.visibility = View.GONE }
        lifecycleScope.launch {
            val transcript = unread.joinToString("\n") {
                "Them: " + it.body.ifBlank { "[sent a picture]" }
            }
            val images = unread.filter { it.imageUri != null }
                .takeLast(3)
                .mapNotNull { m -> repo.loadImageForClaude(m.imageUri!!) }
            val name = repo.displayName(address.orEmpty())
            runCatching { claude.summarize(name, transcript, images) }
                .onSuccess { binding.summaryText.text = it }
                .onFailure { binding.summaryBar.visibility = View.GONE }
        }
    }

    /** Summarize button: a short, at-a-glance summary of just the recent
     *  messages (those since my last reply), shown in the header bar. */
    private fun summarizeRecent() {
        if (!SettingsStore(this).hasApiKey) {
            toast(getString(R.string.no_api_key_short))
            return
        }
        binding.summaryBar.visibility = View.VISIBLE
        binding.summaryText.text = getString(R.string.summarizing)
        binding.summaryBar.setOnClickListener { binding.summaryBar.visibility = View.GONE }
        lifecycleScope.launch {
            val msgs = if (threadId > 0) repo.loadMessages(threadId) else emptyList()
            if (msgs.isEmpty()) {
                binding.summaryBar.visibility = View.GONE
                toast(getString(R.string.no_history))
                return@launch
            }
            // Everything after my last sent message; fall back to the last few.
            val lastMine = msgs.indexOfLast { !it.incoming }
            val recent = if (lastMine in 0 until msgs.size - 1) {
                msgs.subList(lastMine + 1, msgs.size)
            } else {
                msgs.takeLast(6)
            }
            val transcript = recent.joinToString("\n") {
                (if (it.incoming) "Them: " else "Me: ") + it.body.ifBlank { "[picture]" }
            }
            val images = recent.filter { it.imageUri != null }
                .takeLast(2)
                .mapNotNull { m -> repo.loadImageForClaude(m.imageUri!!) }
            val name = repo.displayName(currentAddress().orEmpty())
            runCatching { claude.summarize(name, transcript, images) }
                .onSuccess { binding.summaryText.text = it }
                .onFailure {
                    binding.summaryBar.visibility = View.GONE
                    toast(it.message ?: "Couldn't reach Claude.")
                }
        }
    }

    private fun currentAddress(): String? {
        val typed = binding.recipient.text?.toString()?.trim()
        return if (!typed.isNullOrBlank()) typed else address
    }

    private fun send() {
        val body = binding.input.text?.toString()?.trim().orEmpty()
        val attachment = pendingAttachment
        if (body.isEmpty() && attachment == null) return
        val addr = currentAddress()
        if (addr.isNullOrBlank()) {
            toast(getString(R.string.enter_recipient))
            return
        }
        address = addr
        if (attachment != null) sendMms(addr, body, attachment) else sendSms(addr, body)
    }

    private fun sendSms(addr: String, body: String) {
        lifecycleScope.launch {
            runCatching { repo.sendMessage(addr, body) }
                .onSuccess {
                    onSent(addr)
                    locallySent.add(SmsMessage(body, System.currentTimeMillis(), incoming = false))
                    reload()
                }
                .onFailure { toast(it.message ?: "Couldn't send message.") }
        }
    }

    /** Send a picture/GIF as MMS, then show it optimistically in the thread. */
    private fun sendMms(addr: String, body: String, attachment: Uri) {
        toast(getString(R.string.sending_picture))
        lifecycleScope.launch {
            val data = repo.readAttachment(attachment)
            if (data == null) {
                toast(getString(R.string.cant_read_image))
                return@launch
            }
            val (bytes, mime) = data
            val sentIntent = PendingIntent.getBroadcast(
                this@ThreadActivity, 0,
                Intent(ACTION_MMS_SENT).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val ok = MmsSender.send(applicationContext, addr, body.ifBlank { null }, bytes, mime, sentIntent)
            if (!ok) {
                toast(getString(R.string.picture_send_failed))
                return@launch
            }
            onSent(addr)
            clearAttachment()
            val cached = repo.cacheAttachment(bytes, mime)
            locallySent.add(
                SmsMessage(body, System.currentTimeMillis(), incoming = false, imageUri = cached, imageType = mime)
            )
            reload()
        }
    }

    private fun onSent(addr: String) {
        binding.input.setText("")
        binding.recipientRow.visibility = View.GONE
        binding.recipientDivider.visibility = View.GONE
        title = repo.displayName(addr)
    }

    private fun runClaude(fillReply: Boolean) {
        val addr = currentAddress()
        if (addr.isNullOrBlank()) {
            toast(getString(R.string.enter_recipient))
            return
        }
        if (!SettingsStore(this).hasApiKey) {
            toast(getString(R.string.no_api_key_short))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val transcript = buildTranscript()
            val images = threadImages()
            if (transcript.isBlank() && images.isEmpty()) {
                setBusy(false)
                toast(getString(R.string.no_history))
                return@launch
            }
            runCatching { claude.analyze(repo.displayName(addr), transcript, images) }
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
            .filter { it.body.isNotBlank() || it.imageUri != null }
            .takeLast(14) // recent context only — keeps replies fast and on-topic
        return msgs.joinToString("\n") {
            val who = if (it.incoming) "Them: " else "Me: "
            who + it.body.ifBlank { "[sent a picture]" }
        }
    }

    /** The most recent pictures in this thread, for Claude to look at. Broken
     *  or never-downloaded MMS images are skipped automatically. */
    private suspend fun threadImages(): List<ClaudeImage> {
        if (threadId <= 0) return emptyList()
        return repo.loadMessages(threadId)
            .filter { it.imageUri != null }
            .takeLast(3)
            .mapNotNull { m -> repo.loadImageForClaude(m.imageUri!!) }
    }

    /** Tap an image bubble → open it full-screen. */
    private fun openImage(item: SmsMessage) {
        val uri = item.imageUri ?: return
        startActivity(
            Intent(this, ImageViewerActivity::class.java)
                .putExtra(ImageViewerActivity.EXTRA_IMAGE_URI, uri)
        )
    }

    private fun showSummary(summary: String) = showInfo(R.string.summary_title, summary)

    private fun showInfo(titleRes: Int, message: String) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(message)
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
        private const val ACTION_MMS_SENT = "com.textoverlay.assistant.MMS_SENT"

        /** A compact set of common emoji for the in-app picker. */
        private val EMOJI = listOf(
            "😀", "😂", "🥰", "😍", "😊", "😎", "😇", "🙂", "😉", "😏", "😴", "🤔",
            "😢", "😭", "😡", "🥳", "😱", "🤯", "🤗", "🙄", "😬", "😅", "😘", "😜",
            "👍", "👎", "👌", "🙏", "👏", "🙌", "💪", "🤝", "✌️", "🤞", "👋", "🤙",
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "💯", "🔥", "✨", "⭐",
            "🎉", "🎂", "🎁", "💐", "🌹", "☀️", "🌙", "⚡", "☕", "🍕", "🍻", "🚗",
            "📞", "📱", "💬", "✅", "❌", "❓", "❗", "💤", "🤣", "😉", "👀", "💀"
        )
    }
}
