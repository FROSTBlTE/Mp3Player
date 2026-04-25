package com.example.mymp3player

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.session.*
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private var controller: MediaController? = null
    private var queueAdapter: QueueAdapter? = null
    private var currentFolderName: String? = null
    private var isShuffleActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val PREFS_NAME = "MusicPrefs"
    private val KEY_FOLDER = "last_folder"
    private val KEY_URI = "last_uri"
    private val KEY_POS = "last_pos"

    private val openFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            loadFromUriRecursive(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            controller = controllerFuture.get()
            controller?.addListener(playerListener)
            setupUI()

            // Only load from prefs if the controller is empty.
            // This prevents the "switch back" when returning from the folder picker.
            if (controller?.mediaItemCount == 0) {
                loadPlaybackState()
            } else {
                // If it's already playing, just refresh the UI
                updateBottomPlayerUI(controller?.currentMediaItem)
                refreshQueueFromController()
                startSeekBarTimer()
            }
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateBottomPlayerUI(mediaItem)
            val index = controller?.currentMediaItemIndex ?: -1
            queueAdapter?.updateActiveIndex(index)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            findViewById<ImageButton>(R.id.btnPlay).setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    private fun updateBottomPlayerUI(item: MediaItem?) {
        val meta = item?.mediaMetadata
        findViewById<TextView>(R.id.tvBottomTitle).text = meta?.title ?: "Unknown"
        findViewById<TextView>(R.id.tvBottomArtist).text = meta?.artist ?: "Unknown"
        Glide.with(this).load(meta?.artworkData ?: item?.requestMetadata?.mediaUri)
            .placeholder(R.drawable.ic_play).into(findViewById(R.id.ivMiniArt))
    }

    private fun setupUI() {
        findViewById<ImageButton>(R.id.btnPlay).setOnClickListener {
            if (controller?.isPlaying == true) controller?.pause() else controller?.play()
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { controller?.seekToNext() }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { controller?.seekToPrevious() }
        findViewById<Button>(R.id.btnPickFolder).setOnClickListener { openFolderLauncher.launch(null) }
        findViewById<Button>(R.id.btnAllMusic).setOnClickListener { loadAllMusic() }

        findViewById<ImageButton>(R.id.btnShuffle).setOnClickListener { view ->
        isShuffleActive = !isShuffleActive
            controller?.shuffleModeEnabled = isShuffleActive
            (view as ImageButton).setColorFilter(if (isShuffleActive) Color.CYAN else Color.GRAY)
        }

        findViewById<SeekBar>(R.id.seekBar).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { controller?.seekTo(s?.progress?.toLong() ?: 0L) }
        })

        findViewById<androidx.appcompat.widget.SearchView>(R.id.searchView).setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                queueAdapter?.filter?.filter(newText)
                return true
            }
        })
    }

    // THIS IS THE FIX FOR UNRESPONSIVENESS: Running scanning in background
    private fun loadFromUriRecursive(treeUri: Uri, seekToUri: String? = null, seekPos: Long = 0L) {
        currentFolderName = treeUri.toString()
        val songList = mutableListOf<MediaItem>()

        lifecycleScope.launch(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
            if (root != null) scanRecursive(root, songList)

            withContext(Dispatchers.Main) {
                if (songList.isNotEmpty()) {
                    controller?.setMediaItems(songList)
                    controller?.prepare()
                    updateQueueUI(songList)

                    // Seek immediately after items are loaded into the controller
                    if (seekToUri != null) {
                        val index = songList.indexOfFirst { it.requestMetadata.mediaUri.toString() == seekToUri }
                        if (index != -1) {
                            controller?.seekTo(index, seekPos)
                        }
                    }
                }
            }
        }
    }

    private fun scanRecursive(folder: DocumentFile, list: MutableList<MediaItem>) {
        folder.listFiles().forEach { file ->
            if (file.isDirectory) scanRecursive(file, list)
            else if (file.name?.lowercase()?.endsWith(".mp3") == true) {
                val mediaItem = createMediaItemFromFile(file)
                list.add(mediaItem)
            }
        }
    }

    private fun createMediaItemFromFile(file: DocumentFile): MediaItem {
        val mmr = MediaMetadataRetriever()
        var title = file.nameWithoutExtension
        var artist = "Unknown"
        var art: ByteArray? = null
        try {
            contentResolver.openFileDescriptor(file.uri, "r")?.use {
                mmr.setDataSource(it.fileDescriptor)
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown"
                art = mmr.embeddedPicture
            }
        } catch (e: Exception) {} finally { mmr.release() }

        return MediaItem.Builder()
            .setUri(file.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build())
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(file.uri).build())
            .build()
    }

    private fun updateQueueUI(list: List<MediaItem>) {
        val rv = findViewById<RecyclerView>(R.id.rvQueue)
        rv.layoutManager = LinearLayoutManager(this)
        queueAdapter = QueueAdapter(list) { index ->
            controller?.seekTo(index, 0L)
            controller?.play()
        }
        rv.adapter = queueAdapter
    }

    private fun startSeekBarTimer() {
        mainHandler.post(object : Runnable {
            override fun run() {
                controller?.let {
                    val sb = findViewById<SeekBar>(R.id.seekBar)
                    sb.max = it.duration.toInt()
                    sb.progress = it.currentPosition.toInt()
                    findViewById<TextView>(R.id.tvCurrentTime).text = formatTime(it.currentPosition)
                    findViewById<TextView>(R.id.tvTotalTime).text = formatTime(it.duration)
                }
                mainHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val sec = (ms / 1000) % 60
        val min = (ms / (1000 * 60)) % 60
        return String.format("%d:%02d", min, sec)
    }

    private fun loadPlaybackState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val folder = prefs.getString(KEY_FOLDER, null)
        val lastUri = prefs.getString(KEY_URI, null)
        val pos = prefs.getLong(KEY_POS, 0L)

        if (folder != null) {
            if (folder == "ALL_MUSIC") {
                loadAllMusic(lastUri, pos)
            } else {
                loadFromUriRecursive(Uri.parse(folder), lastUri, pos)
            }
            startSeekBarTimer()
        }
    }

    override fun onStop() {
        // Save state whenever the app goes to background
        val currentItem = controller?.currentMediaItem
        if (currentItem != null) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            prefs.putString(KEY_FOLDER, currentFolderName)
            prefs.putString(KEY_URI, currentItem.requestMetadata.mediaUri.toString())
            prefs.putLong(KEY_POS, controller?.currentPosition ?: 0L)
            prefs.apply()
        }

        super.onStop()
    }

    private fun refreshQueueFromController() {
        val list = mutableListOf<MediaItem>()
        for (i in 0 until (controller?.mediaItemCount ?: 0)) {
            controller?.getMediaItemAt(i)?.let { list.add(it) }
        }
        if (list.isNotEmpty()) updateQueueUI(list)
    }

    private fun checkPermissions() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()){}.launch(perm)
        }
    }

    private fun loadAllMusic(seekToUri: String? = null, seekPos: Long = 0L) { }
}

val DocumentFile.nameWithoutExtension: String get() = name?.substringBeforeLast(".") ?: ""