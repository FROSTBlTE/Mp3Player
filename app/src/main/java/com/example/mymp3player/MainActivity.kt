package com.example.mymp3player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import android.provider.MediaStore
import android.content.ContentUris
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import android.graphics.Color
import android.widget.ImageButton
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

private const val PREFS_NAME = "MusicPlayerPrefs"
private const val KEY_URI = "last_uri"
private const val KEY_POS = "last_pos"
private const val KEY_TITLE = "last_title"
private const val KEY_ARTIST = "last_artist"
private const val KEY_FOLDER = "last_folder"

class MainActivity : AppCompatActivity() {
    private var controller: MediaController? = null
    private var queueAdapter: QueueAdapter? = null
    private var isShuffleActive = false // Our "Master State"
    private var currentFolderName: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permission Denied. Cannot play music.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This connects the Kotlin code to your XML file
        setContentView(R.layout.activity_main)

        // 2. Check if we need to ask for permission
        checkPermissions()
    }

    private fun checkPermissions() {
        // In Android 13 (API 33) and above, we use READ_MEDIA_AUDIO
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            // For older phones, we use READ_EXTERNAL_STORAGE
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            // If already granted, do nothing
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                // Already have permission
            }
            // Otherwise, trigger the popup
            else -> {
                requestPermissionLauncher.launch(permission)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // This is like setting up a "Client" to talk to your PlaybackService "Server"
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        controllerFuture.addListener({
            controller = controllerFuture.get()

            // Restores the last song and state right before the app closed
            loadPlaybackState()

            // Sync UI with the actual state of the controller
            val isShuffleOn = controller?.shuffleModeEnabled ?: false
            findViewById<ImageButton>(R.id.btnShuffle).setColorFilter(
                if (isShuffleOn) Color.CYAN else Color.GRAY
            )

            // Set repeat to ALL by default for car use
            controller?.repeatMode = Player.REPEAT_MODE_ALL
            findViewById<ImageButton>(R.id.btnRepeat).setColorFilter(Color.CYAN)

            setupButtons()

            controller?.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                    // This triggers when the song changes
                    findViewById<TextView>(R.id.tvBottomTitle).text = metadata.title ?: "Unknown Title"
                    findViewById<TextView>(R.id.tvBottomArtist).text = metadata.artist ?: "Unknown Artist"

                    // Update the Mini Album Art at the bottom
                    val ivMiniArt = findViewById<ImageView>(R.id.ivMiniArt)
                    if (ivMiniArt != null) {
                        Glide.with(this@MainActivity)
                            .load(metadata.artworkUri)
                            .error(R.drawable.ic_play)
                            .into(ivMiniArt)
                    }
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    findViewById<ImageButton>(R.id.btnShuffle).setColorFilter(
                        if (shuffleModeEnabled) Color.CYAN else Color.GRAY
                    )
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    val color = when (repeatMode) {
                        Player.REPEAT_MODE_ALL -> Color.CYAN
                        Player.REPEAT_MODE_ONE -> Color.GREEN
                        else -> Color.GRAY
                    }
                    findViewById<ImageButton>(R.id.btnRepeat).setColorFilter(color)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val playBtn = findViewById<ImageButton>(R.id.btnPlay)
                    if (isPlaying) {
                        playBtn.setImageResource(R.drawable.ic_pause)
                    } else {
                        playBtn.setImageResource(R.drawable.ic_play)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // This handles the highlight in the queue we fixed earlier
                    val recyclerView = findViewById<RecyclerView>(R.id.rvQueue)
                    recyclerView.post {
                        val currentIndex = controller?.currentMediaItemIndex ?: -1
                        queueAdapter?.updateActiveIndex(currentIndex)
                        if (currentIndex != -1) {
                            recyclerView.smoothScrollToPosition(currentIndex)
                        }
                    }
                }
            })
        }, MoreExecutors.directExecutor())

        val seekBar = findViewById<SeekBar>(R.id.seekBar)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        mainHandler.post(object : Runnable {
            override fun run() {
                controller?.let {
                    if (it.duration > 0) {
                        seekBar.max = it.duration.toInt()
                    }

                    if (it.playbackState != Player.STATE_IDLE) {
                        seekBar.progress = it.currentPosition.toInt()
                    }

                    if (it.isPlaying) {
                        seekBar.max = it.duration.toInt()
                        seekBar.progress = it.currentPosition.toInt()
                    }

                    val currentPos = it.currentPosition
                    val duration = it.duration

                    if (duration > 0) {
                        findViewById<SeekBar>(R.id.seekBar).max = duration.toInt()
                        findViewById<TextView>(R.id.tvTotalTime).text = formatTime(duration)
                    }

                    findViewById<SeekBar>(R.id.seekBar).progress = currentPos.toInt()
                    findViewById<TextView>(R.id.tvCurrentTime).text = formatTime(currentPos)
                }
                mainHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnPickFolder).setOnClickListener {
            loadMusicFromStorage()
        }

        val playBtn = findViewById<ImageButton>(R.id.btnPlay)
        playBtn.setOnClickListener {
            // In Kotlin, the '?' is the same as C# Null-conditional operator
            if (controller?.isPlaying == true) {
                controller?.pause()
            } else {
                controller?.play()
            }
        }

        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            // This method is the best for handling Shuffle + Repeat All
            if (controller?.hasNextMediaItem() == true) {
                controller?.seekToNext()
            } else {
                // If it somehow gets stuck at the end, jump to start
                controller?.seekTo(0, 0)
            }
        }

        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            controller?.seekToPrevious()
        }

        val btnShuffle = findViewById<ImageButton>(R.id.btnShuffle)
        val btnRepeat = findViewById<ImageButton>(R.id.btnRepeat)

        // Shuffle Logic
        btnShuffle.setOnClickListener {
            isShuffleActive = !isShuffleActive // Toggle our master variable
            controller?.shuffleModeEnabled = isShuffleActive

            // Update the UI icon color
            btnShuffle.setColorFilter(if (isShuffleActive) Color.CYAN else Color.GRAY)

            Toast.makeText(this, "Shuffle ${if (isShuffleActive) "On" else "Off"}", Toast.LENGTH_SHORT).show()
        }

        // Repeat Logic (Cycles: Off -> All -> One)
        btnRepeat.setOnClickListener {
            val currentMode = controller?.repeatMode ?: Player.REPEAT_MODE_OFF
            val nextMode = when (currentMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }

            controller?.repeatMode = nextMode

            // Visual feedback
            when (nextMode) {
                Player.REPEAT_MODE_OFF -> btnRepeat.setColorFilter(Color.GRAY)
                Player.REPEAT_MODE_ALL -> btnRepeat.setColorFilter(Color.CYAN)
                Player.REPEAT_MODE_ONE -> btnRepeat.setColorFilter(Color.GREEN)
            }
        }

        findViewById<Button>(R.id.btnPickFolder).setOnClickListener {
            val folderList = getMusicFolders()

            if (folderList.isEmpty()) {
                Toast.makeText(this, "No music folders found!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val builder = android.app.AlertDialog.Builder(this@MainActivity)
            builder.setTitle("Select a Music Folder")

            // .setItems creates a clickable list
            builder.setItems(folderList.toTypedArray()) { _, which ->
                val selectedFolder = folderList[which]
                loadMusicFromStorage(selectedFolder)
            }

            builder.show()
        }

        val seekBar = findViewById<SeekBar>(R.id.seekBar)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // If the user is dragging the thumb, we might want to update a "current time" label
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                // Optional: stop the auto-update timer here if you want to be fancy
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                // THIS is where the magic happens.
                // When the user lets go, tell the player to jump to that spot.
                controller?.seekTo(sb?.progress?.toLong() ?: 0L)
            }
        })

        findViewById<Button>(R.id.btnAllMusic).setOnClickListener {
            loadMusicFromStorage(null) // Passing null triggers the 'all music' query
        }
    }

    private fun loadMusicFromStorage(folderName: String? = null) {
        currentFolderName = folderName
        val songList = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )

        // Filter by folder if name provided
        val selection = if (folderName != null) "${MediaStore.Audio.Media.DATA} LIKE ?" else null
        val selectionArgs = if (folderName != null) arrayOf("%/$folderName/%") else null

        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, null
        )

        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIdCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (it.moveToNext()) {
                val albumId = it.getLong(albumIdCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.getLong(idCol))
                val artworkUri = ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )

                // Setting metadata is what sends info to your Car's screen!
                val metadata = MediaMetadata.Builder()
                    .setTitle(it.getString(titleCol))
                    .setArtist(it.getString(artistCol))
                    .setArtworkUri(artworkUri)
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(metadata)
                    .build()

                songList.add(mediaItem)
            }
        }

        if (songList.isNotEmpty()) {
            controller?.setMediaItems(songList)
            controller?.repeatMode = Player.REPEAT_MODE_ALL
            controller?.shuffleModeEnabled = isShuffleActive

            if (isShuffleActive && songList.size > 1) {
                val randomIndex = (0 until songList.size).random()
                controller?.seekTo(randomIndex, 0L)
            }

            controller?.prepare()

            updateQueueUI(songList)
            Toast.makeText(this, "Playing $folderName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMusicFolders(): List<String> {
        val folders = mutableSetOf<String>() // Set ensures no duplicates
        val projection = arrayOf(MediaStore.Audio.Media.DATA)

        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        )

        cursor?.use {
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (it.moveToNext()) {
                val filePath = it.getString(dataCol)
                // Get the name of the parent folder
                val folder = java.io.File(filePath).parentFile?.name ?: "Unknown"
                folders.add(folder)
            }
        }
        return folders.toList().sorted()
    }

    private fun updateQueueUI(items: List<MediaItem>) {
        val recyclerView = findViewById<RecyclerView>(R.id.rvQueue)
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        // Create the adapter and tell it what to do when a song is clicked
        queueAdapter = QueueAdapter(items) { clickedIndex ->
            // This is the "Click Action" - jump to the clicked song and play
            controller?.seekTo(clickedIndex, 0)
            controller?.play()
        }

        recyclerView.adapter = queueAdapter

        // Set initial highlight if a song is already loaded
        val startIndex = controller?.currentMediaItemIndex ?: -1
        queueAdapter?.updateActiveIndex(startIndex)
    }

    private fun savePlaybackState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val editor = prefs.edit()

        val currentItem = controller?.currentMediaItem
        if (currentItem != null) {
            editor.putString(KEY_FOLDER, currentFolderName ?: "ALL_MUSIC")
            editor.putString(KEY_URI, currentItem.localConfiguration?.uri.toString())
            editor.putLong(KEY_POS, controller?.currentPosition ?: 0L)
            editor.putString(KEY_TITLE, currentItem.mediaMetadata.title?.toString())
            editor.putString(KEY_ARTIST, currentItem.mediaMetadata.artist?.toString())
            editor.apply() // .apply() is like C#'s async Save
        }

        // Inside savePlaybackState()
        // Add this line to the editor
        editor.putString(KEY_FOLDER, currentFolderName) // You'll need to store currentFolderName as a class variable
    }

    private fun loadPlaybackState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastFolder = prefs.getString(KEY_FOLDER, null)
        val lastUri = prefs.getString(KEY_URI, null)
        val lastPos = prefs.getLong(KEY_POS, 0L)

        if (lastFolder != null) {
            // 1. Load the folder (Only call this once!)
            if (lastFolder == "ALL_MUSIC") {
                loadMusicFromStorage(null)
            } else {
                loadMusicFromStorage(lastFolder)
            }

            // 2. Wait for the player to be ready to restore position and UI
            controller?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {

                        // A. Find the last song index by its URI
                        for (i in 0 until (controller?.mediaItemCount ?: 0)) {
                            if (controller?.getMediaItemAt(i)?.localConfiguration?.uri.toString() == lastUri) {
                                controller?.seekTo(i, lastPos)
                                break
                            }
                        }

                        // B. UPDATE THE UI LABELS HERE (The fix for your "Title" issue)
                        val metadata = controller?.currentMediaItem?.mediaMetadata
                        if (metadata != null) {
                            findViewById<TextView>(R.id.tvBottomTitle).text = metadata.title ?: "Unknown Title"
                            findViewById<TextView>(R.id.tvBottomArtist).text = metadata.artist ?: "Unknown Artist"

                            // Update the mini-player image too
                            val miniArt = findViewById<ImageView>(R.id.ivMiniArt)
                            if (miniArt != null) {
                                Glide.with(this@MainActivity)
                                    .load(metadata.artworkUri)
                                    .error(R.drawable.ic_play)
                                    .into(miniArt)
                            }
                        }

                        // C. Sync the Seekbar
                        findViewById<SeekBar>(R.id.seekBar).progress = lastPos.toInt()

                        // D. Remove this listener so it doesn't fire again
                        controller?.removeListener(this)
                    }
                }
            })
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onStop() {
        savePlaybackState() // Save before releasing the connection
        // Clean up the connection when the app is closed
        controller?.release()
        controller = null
        super.onStop()
    }
}