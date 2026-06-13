package com.textoverlay.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.textoverlay.assistant.databinding.ActivitySettingsBinding

/** Claude API key and reply-tone settings. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.apiKeyInput.setText(settings.apiKey)
        binding.toneInput.setText(settings.tone)

        binding.saveButton.setOnClickListener {
            settings.apiKey = binding.apiKeyInput.text?.toString().orEmpty()
            val tone = binding.toneInput.text?.toString()?.trim().orEmpty()
            settings.tone = tone.ifBlank { SettingsStore.DEFAULT_TONE }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
