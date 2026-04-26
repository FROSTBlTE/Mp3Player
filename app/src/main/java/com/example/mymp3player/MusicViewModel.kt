package com.example.mymp3player

import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem

class MusicViewModel : ViewModel() {
    // This caches the songs in memory so they survive minimizing the app
    var cachedSongList: List<MediaItem>? = null
    var currentFolder: String? = null
}