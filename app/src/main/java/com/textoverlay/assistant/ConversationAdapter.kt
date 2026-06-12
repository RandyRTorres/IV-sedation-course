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
            binding.initials.text = initialsOf(item.displayName)
            binding.snippet.text = item.snippet
            binding.time.text = DateUtils.getRelativeTimeSpanString(
                item.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            binding.unreadDot.visibility =
                if (item.unread) android.view.View.VISIBLE else android.view.View.GONE
            binding.snippet.setTypeface(
                null,
                if (item.unread) android.graphics.Typeface.BOLD
                else android.graphics.Typeface.NORMAL
            )
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    /** Up to two uppercase initials for the avatar; falls back to a glyph. */
    private fun initialsOf(name: String): String {
        val words = name.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.first().isLetter() }
        return when {
            words.size >= 2 -> "${words.first().first()}${words.last().first()}".uppercase()
            words.size == 1 -> words.first().take(2).uppercase()
            else -> name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
        }
    }
}
