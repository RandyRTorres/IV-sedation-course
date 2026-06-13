package com.textoverlay.assistant

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView

/**
 * Autocomplete for the "To" field: as the user types a name or number, query
 * contacts and offer matches. Selecting a row puts the phone number in the
 * field so the message can be sent.
 */
class ContactCompletionAdapter(context: Context) :
    ArrayAdapter<ContactCompletionAdapter.Item>(context, android.R.layout.simple_list_item_2) {

    /** toString() returns the number so the field holds a sendable address. */
    data class Item(val name: String, val number: String) {
        override fun toString() = number
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        getItem(position)?.let { item ->
            view.findViewById<TextView>(android.R.id.text1).text = item.name
            view.findViewById<TextView>(android.R.id.text2).text = item.number
        }
        return view
    }

    override fun getFilter(): Filter = filter

    private val filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val items = ArrayList<Item>()
            if (!constraint.isNullOrBlank()) {
                val uri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                    Uri.encode(constraint.toString())
                )
                runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        null, null, null
                    )?.use { c ->
                        val seen = HashSet<String>()
                        while (c.moveToNext() && items.size < 8) {
                            val name = c.getString(0) ?: continue
                            val number = c.getString(1) ?: continue
                            val key = number.filter { it.isDigit() }
                            if (key.isNotEmpty() && seen.add(key)) items.add(Item(name, number))
                        }
                    }
                }
            }
            return FilterResults().apply { values = items; count = items.size }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            clear()
            @Suppress("UNCHECKED_CAST")
            (results?.values as? List<Item>)?.let { addAll(it) }
            notifyDataSetChanged()
        }
    }
}
