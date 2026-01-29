package com.example.mymp3player

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class QueueAdapter(
    private val items: List<MediaItem>,
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    private var selectedIndex: Int = -1

    // 1. Update the ViewHolder to find the NEW IDs from item_song.xml
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAlbumArt: ImageView = view.findViewById(R.id.ivAlbumArt)
        val tvTitle: TextView = view.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvArtistName)
    }

    fun updateActiveIndex(newIndex: Int) {
        val oldIndex = selectedIndex
        selectedIndex = newIndex
        notifyItemChanged(oldIndex)
        notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // 2. Ensure we are inflating the modern item_song layout
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val mediaItem = items[position]
        val metadata = mediaItem.mediaMetadata

        // 3. Set text using the references in the ViewHolder
        holder.tvTitle.text = metadata.title ?: "Unknown Title"
        holder.tvArtist.text = metadata.artist ?: "Unknown Artist"

        // 4. Use Glide to load the art safely
        // Glide handles null URIs automatically, but the 'into' target MUST not be null
        Glide.with(holder.itemView.context)
            .load(metadata.artworkUri)
            .placeholder(R.drawable.ic_play) // Shows while loading or if missing
            .error(R.drawable.ic_play)       // Shows if loading fails
            .into(holder.ivAlbumArt)         // This reference is now safe

        // Highlight logic
        if (position == selectedIndex) {
            holder.tvTitle.setTextColor(Color.parseColor("#FFC107")) // Yellow accent
            holder.tvTitle.setTypeface(null, Typeface.BOLD)
        } else {
            holder.tvTitle.setTextColor(Color.WHITE)
            holder.tvTitle.setTypeface(null, Typeface.NORMAL)
        }

        holder.itemView.setOnClickListener {
            onItemClick(position)
        }
    }

    override fun getItemCount() = items.size
}