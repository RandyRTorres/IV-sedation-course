package com.textoverlay.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.textoverlay.assistant.databinding.ItemMessageBinding

/** Renders messages in a thread, aligning received vs. sent bubbles and showing
 *  any MMS image. Tap an image to open it; long-press a bubble to copy its text. */
class MessageAdapter(
    private val onImageClick: (SmsMessage) -> Unit = {}
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = ArrayList<SmsMessage>()

    /** Multiplier applied to message text size (pinch-to-zoom). */
    var textScale: Float = 1f
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(newItems: List<SmsMessage>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SmsMessage) {
            // Text portion
            if (item.body.isBlank()) {
                binding.text.visibility = View.GONE
            } else {
                binding.text.visibility = View.VISIBLE
                binding.text.text = item.body
                binding.text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * textScale)
            }

            // Image / video portion (MMS)
            if (item.imageUri != null) {
                binding.image.visibility = View.VISIBLE
                if (item.imageType?.startsWith("video/") == true) {
                    binding.image.setImageResource(R.drawable.ic_videocam)
                } else {
                    runCatching { binding.image.setImageURI(Uri.parse(item.imageUri)) }
                }
                binding.image.setOnClickListener { onImageClick(item) }
            } else {
                binding.image.visibility = View.GONE
                binding.image.setImageDrawable(null)
                binding.image.setOnClickListener(null)
            }

            // Alignment, bubble colour, and text colour
            val params = binding.bubble.layoutParams as FrameLayout.LayoutParams
            if (item.incoming) {
                params.gravity = Gravity.START
                binding.bubble.setBackgroundResource(R.drawable.bubble_in)
                binding.text.setTextColor(0xFF000000.toInt())
            } else {
                params.gravity = Gravity.END
                binding.bubble.setBackgroundResource(R.drawable.bubble_out)
                binding.text.setTextColor(0xFFFFFFFF.toInt())
            }
            binding.bubble.layoutParams = params

            // Long-press a bubble to copy its text.
            binding.bubble.setOnLongClickListener {
                if (item.body.isNotBlank()) copyToClipboard(binding.root.context, item.body)
                true
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }
}
