package com.example.mymp3player

import android.graphics.Color
import android.graphics.Typeface
import android.view.*
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class QueueAdapter(private val fullList: List<MediaItem>, private val onClick: (Int) -> Unit) :
    RecyclerView.Adapter<QueueAdapter.VH>(), Filterable {

    private var displayList = fullList
    private var selectedIdx = -1

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val art: ImageView = v.findViewById(R.id.ivAlbumArt)
        val title: TextView = v.findViewById(R.id.tvSongTitle)
        val artist: TextView = v.findViewById(R.id.tvArtistName)
    }

    fun updateActiveIndex(i: Int) {
        val old = selectedIdx; selectedIdx = i
        notifyItemChanged(old); notifyItemChanged(selectedIdx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = displayList[position]
        holder.title.text = item.mediaMetadata.title
        holder.artist.text = item.mediaMetadata.artist

        Glide.with(holder.itemView).load(item.mediaMetadata.artworkData ?: item.requestMetadata.mediaUri)
            .placeholder(R.drawable.ic_play).into(holder.art)

        val isSel = fullList.indexOf(item) == selectedIdx
        holder.title.setTextColor(if (isSel) Color.parseColor("#FFC107") else Color.WHITE)
        holder.title.setTypeface(null, if (isSel) Typeface.BOLD else Typeface.NORMAL)

        holder.itemView.setOnClickListener { onClick(fullList.indexOf(item)) }
    }

    override fun getItemCount() = displayList.size

    override fun getFilter(): android.widget.Filter {
        return object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                val query = constraint?.toString()?.lowercase() ?: ""

                // We use a manual list and a loop to avoid the compiler recursion bug
                val filteredList = mutableListOf<MediaItem>()

                if (query.isEmpty()) {
                    filteredList.addAll(fullList)
                } else {
                    // Manual for-loop is the safest way to fix the 'Type Checking' error
                    for (item in fullList) {
                        val title = item.mediaMetadata.title?.toString()?.lowercase() ?: ""
                        val artist = item.mediaMetadata.artist?.toString()?.lowercase() ?: ""

                        if (title.contains(query) || artist.contains(query)) {
                            filteredList.add(item)
                        }
                    }
                }

                results.values = filteredList
                results.count = filteredList.size
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                // Safe cast using the 'as?' operator
                val newDisplayList = results?.values as? List<MediaItem>
                displayList = newDisplayList ?: fullList
                notifyDataSetChanged()
            }
        }
    }
}