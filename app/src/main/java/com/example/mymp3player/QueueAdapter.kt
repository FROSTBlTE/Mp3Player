package com.example.mymp3player

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.RecyclerView

class QueueAdapter(
    private val items: List<MediaItem>,
    private val onItemClick: (Int) -> Unit // Callback function (Like a Delegate)
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    private var selectedIndex: Int = -1

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.text1)
        val artist: TextView = view.findViewById(android.R.id.text2)
    }

    // Call this from MainActivity when the song changes
    fun updateActiveIndex(newIndex: Int) {
        val oldIndex = selectedIndex
        selectedIndex = newIndex
        notifyItemChanged(oldIndex) // Refresh old row (remove highlight)
        notifyItemChanged(newIndex) // Refresh new row (add highlight)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val metadata = items[position].mediaMetadata
        holder.title.text = metadata.title ?: "Unknown"
        holder.artist.text = metadata.artist ?: "Unknown"

        // Update UI based on if this song is currently playing
        if (position == selectedIndex) {
            holder.title.setTextColor(Color.CYAN) // Highlight color
            holder.title.setTypeface(null, Typeface.BOLD)
        } else {
            holder.title.setTextColor(Color.WHITE)
            holder.title.setTypeface(null, Typeface.NORMAL)
        }

        // Handle Clicks
        holder.itemView.setOnClickListener {
            onItemClick(position)
        }
    }

    override fun getItemCount() = items.size
}