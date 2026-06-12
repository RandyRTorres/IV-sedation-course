package com.textoverlay.assistant

import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.textoverlay.assistant.databinding.ItemMessageBinding

/** Renders messages in a thread, aligning received vs. sent bubbles and showing
 *  any MMS image. Tapping an image asks Claude to describe it. */
class MessageAdapter(
    private val onImageClick: (SmsMessage) -> Unit = {}
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = ArrayList<SmsMessage>()

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
            }

            // Image portion (MMS)
            if (item.imageUri != null) {
                binding.image.visibility = View.VISIBLE
                runCatching { binding.image.setImageURI(Uri.parse(item.imageUri)) }
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
        }
    }
}
