package com.textoverlay.assistant

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.textoverlay.assistant.databinding.ItemConversationBinding

/** Renders the list of SMS threads. */
class ConversationAdapter(
    private val onClick: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    private val items = ArrayList<Conversation>()

    fun submit(newItems: List<Conversation>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val binding: ItemConversationBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Conversation) {
            binding.name.text = item.displayName
            binding.snippet.text = item.snippet
            binding.time.text = DateUtils.getRelativeTimeSpanString(
                item.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            val emphasis = if (item.unread) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL
            binding.name.setTypeface(null, emphasis)
            binding.snippet.setTypeface(null, emphasis)
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
