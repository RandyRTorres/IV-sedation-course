package com.textoverlay.assistant

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.textoverlay.assistant.databinding.ItemMessageBinding

/** Renders messages in a thread, aligning received vs. sent bubbles. */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

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
            binding.bubble.text = item.body
            val params = binding.bubble.layoutParams as FrameLayout.LayoutParams
            if (item.incoming) {
                params.gravity = Gravity.START
                binding.bubble.setBackgroundResource(R.drawable.bubble_in)
            } else {
                params.gravity = Gravity.END
                binding.bubble.setBackgroundResource(R.drawable.bubble_out)
            }
            binding.bubble.layoutParams = params
        }
    }
}
