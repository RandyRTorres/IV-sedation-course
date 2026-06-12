package com.textoverlay.assistant

import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.app.AppCompatActivity
import com.textoverlay.assistant.databinding.ActivityImageViewerBinding

/** Full-screen photo viewer with pinch-to-zoom, drag, double-tap and tap-to-close. */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private var scale = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                scale = (scale * d.scaleFactor).coerceIn(1f, 5f)
                binding.image.scaleX = scale
                binding.image.scaleY = scale
                if (scale == 1f) { binding.image.translationX = 0f; binding.image.translationY = 0f }
                return true
            }
        })
    }

    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                finish(); return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                scale = if (scale > 1f) 1f else 2.5f
                binding.image.scaleX = scale
                binding.image.scaleY = scale
                if (scale == 1f) { binding.image.translationX = 0f; binding.image.translationY = 0f }
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uri == null) { finish(); return }
        runCatching { binding.image.setImageURI(Uri.parse(uri)) }

        binding.close.setOnClickListener { finish() }
        binding.image.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            // Drag only while zoomed in
            if (scale > 1f && !scaleDetector.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        binding.image.translationX += event.rawX - lastX
                        binding.image.translationY += event.rawY - lastY
                        lastX = event.rawX; lastY = event.rawY
                    }
                }
            }
            true
        }
    }

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
    }
}
