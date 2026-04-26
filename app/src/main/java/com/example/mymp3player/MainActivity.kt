package com.example.mymp3player

import android.Manifest
import android.content.*
import android.content.ContentUris
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
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private var controller: MediaController? = null
    private var queueAdapter: QueueAdapter? = null
    private var currentFolderName: String? = null
    private var isShuffleActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var loadingJob: Job? = null

    private val PREFS_NAME = "MusicPrefs"
    private val KEY_FOLDER = "last_folder"
    private val KEY_URI = "last_uri"
    private val KEY_POS = "last_pos"

    private val viewModel: MusicViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[MusicViewModel::class.java]
    }

    private val openFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Update variables and save immediately
            currentFolderName = it.toString()
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            prefs.putString(KEY_FOLDER, currentFolderName)
            prefs.apply()

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

        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.let { future ->
            future.addListener({
                try {
                    controller = future.get()
                    controller?.addListener(playerListener)
                    setupUI()

                    // CHECK FOR EXTERNAL INTENT FIRST
                    if (intent?.action == Intent.ACTION_VIEW) {
                        handleExternalIntent(intent)
                        // Clear the intent action so it doesn't trigger again on rotate
                        intent.action = null
                    } else
                    // 1. Check if the Service already has music (it was playing in background)
                    if (controller?.mediaItemCount ?: 0 > 0) {
                        refreshQueueFromController()
                        updateBottomPlayerUI(controller?.currentMediaItem)
                    }
                    // 2. Check if we have the list cached in our ViewModel (Warm Start)
                    else if (viewModel.cachedSongList != null) {
                        val list = viewModel.cachedSongList!!
                        currentFolderName = viewModel.currentFolder
                        controller?.setMediaItems(list)
                        controller?.prepare()
                        updateQueueUI(list) // This refreshes the RecyclerView

                        // Seek to where we were
                        loadPlaybackStateOnlySeek()
                    }
                    else{
                        loadPlaybackState()
                    }

                    startSeekBarTimer()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, MoreExecutors.directExecutor())
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity intent
        if (intent?.action == Intent.ACTION_VIEW) {
            handleExternalIntent(intent)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateBottomPlayerUI(mediaItem)
            val index = controller?.currentMediaItemIndex ?: -1

            if (index != -1) {
                // 1. Update the highlight color in the adapter
                queueAdapter?.updateActiveIndex(index)

                val searchView = findViewById<androidx.appcompat.widget.SearchView>(R.id.searchView)
                if (searchView.query.isNullOrEmpty()) {
                    findViewById<RecyclerView>(R.id.rvQueue).smoothScrollToPosition(index)
                }
            }

            saveCurrentState()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            findViewById<ImageButton>(R.id.btnPlay).setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            if (!isPlaying) {
                saveCurrentState()
            }
        }
    }

    private fun updateBottomPlayerUI(item: MediaItem?) {
        if (isFinishing || isDestroyed) return

        val meta = item?.mediaMetadata
        findViewById<TextView>(R.id.tvBottomTitle).text = meta?.title ?: "Unknown"
        findViewById<TextView>(R.id.tvBottomArtist).text = meta?.artist ?: "Unknown"

        Glide.with(this)
            .load(meta?.artworkData ?: item?.requestMetadata?.mediaUri)
            .signature(com.bumptech.glide.signature.ObjectKey(item?.requestMetadata?.mediaUri.toString()))
            .placeholder(R.drawable.ic_play)
            .into(findViewById(R.id.ivMiniArt))
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

    private fun loadFromUriRecursive(treeUri: Uri, seekToUri: String? = null, seekPos: Long = 0L) {
        currentFolderName = treeUri.toString()
        val songList = mutableListOf<MediaItem>()

        loadingJob?.cancel()

        loadingJob = lifecycleScope.launch(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(this@MainActivity, treeUri)

            if (root == null) {
                withContext(Dispatchers.Main) { updateLoadingProgress(1, 1) }
                return@launch
            }

            val allFiles = mutableListOf<DocumentFile>()
            fun collect(f: DocumentFile) {
                f.listFiles().forEach { if (it.isDirectory) collect(it) else if (it.name?.endsWith(".mp3") == true) allFiles.add(it) }
            }
            collect(root)

            val total = allFiles.size
            if (total == 0) {
                withContext(Dispatchers.Main) {
                    updateLoadingProgress(1, 1)
                    Toast.makeText(this@MainActivity, "No MP3s found in this folder", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val mmr = MediaMetadataRetriever()

            allFiles.forEachIndexed { index, file ->
                yield()
                withContext(Dispatchers.Main) { updateLoadingProgress(index + 1, total) }

                // Your createMediaItemFromFile logic here...
                val mediaItem = createMediaItemFromFile(file)
                songList.add(mediaItem)
            }
            mmr.release()

            withContext(Dispatchers.Main) {
                updateLoadingProgress(1,1)
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
        // Cache the list in the ViewModel
        viewModel.cachedSongList = list
        viewModel.currentFolder = currentFolderName

        val rv = findViewById<RecyclerView>(R.id.rvQueue)
        rv.layoutManager = LinearLayoutManager(this)
        queueAdapter = QueueAdapter(list) { index ->
            controller?.seekTo(index, 0L)
            controller?.play()
        }

        val currentIndex = controller?.currentMediaItemIndex ?: -1
        queueAdapter?.updateActiveIndex(currentIndex)

        rv.adapter = queueAdapter

        // Scroll to it if it's not the first song
        if (currentIndex > 0) {
            rv.scrollToPosition(currentIndex)
        }
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
            currentFolderName = folder

            when (folder) {
                "ALL_MUSIC" -> loadAllMusic(lastUri, pos)
                "EXTERNAL_FILE" -> {
                    lastUri?.let { uriStr ->
                        try {
                            handleExternalIntent(Intent().apply { data = Uri.parse(uriStr) })
                        } catch (e: Exception) {
                            // If the permission expired, just load All Music instead of crashing
                            //loadAllMusic()
                            Toast.makeText(
                                this,
                                "Failed to load external file!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                else -> loadFromUriRecursive(Uri.parse(folder), lastUri, pos)
            }
            startSeekBarTimer()
        }
    }

    override fun onStop() {
        saveCurrentState()

        controller?.removeListener(playerListener)
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        controller = null

        super.onStop()
    }

    private fun saveCurrentState() {
        val currentController = controller ?: return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()

        currentFolderName?.let {
            prefs.putString(KEY_FOLDER, it)
        }

        val currentItem = currentController?.currentMediaItem
        if (currentItem != null) {
            prefs.putString(KEY_URI, currentItem.requestMetadata.mediaUri.toString())
            prefs.putLong(KEY_POS, currentController.currentPosition)
        }

        prefs.apply()
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

    private fun loadAllMusic(seekToUri: String? = null, seekPos: Long = 0L) {
        currentFolderName = "ALL_MUSIC"
        val songList = mutableListOf<MediaItem>()
        loadingJob?.cancel()

        Toast.makeText(this, "Scanning all music (this may take a moment)...", Toast.LENGTH_SHORT).show()

        loadingJob = lifecycleScope.launch(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA, // We need the file path/data to extract art
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

                val total = cursor.count
                var count = 0
                val mmr = MediaMetadataRetriever()
                while (cursor.moveToNext()) {
                    yield()
                    count++
                    withContext(Dispatchers.Main) { updateLoadingProgress(count, total) }

                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    var title = cursor.getString(titleCol) ?: "Unknown"
                    var artist = cursor.getString(artistCol) ?: "Unknown"
                    var art: ByteArray? = null

                    // MANUALLY EXTRACT UNIQUE ART for this specific file ID
                    try {
                        contentResolver.openFileDescriptor(contentUri, "r")?.use { pfd ->
                            mmr.setDataSource(pfd.fileDescriptor)
                            art = mmr.embeddedPicture
                            // Optional: fill in missing title/artist from file tags if MediaStore is empty
                            if (title == "Unknown") title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "Unknown"
                        }
                    } catch (e: Exception) { /* Skip if file unreadable */ }

                    val mediaItem = MediaItem.Builder()
                        .setUri(contentUri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(title)
                                .setArtist(artist)
                                .setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .build()
                        )
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder().setMediaUri(contentUri).build()
                        )
                        .build()
                    songList.add(mediaItem)
                }
                mmr.release()
            }

            withContext(Dispatchers.Main) {
                updateLoadingProgress(1,1)
                if (songList.isNotEmpty()) {
                    controller?.setMediaItems(songList)
                    controller?.prepare()
                    updateQueueUI(songList)
                    if (seekToUri != null) {
                        val index = songList.indexOfFirst { it.requestMetadata.mediaUri.toString() == seekToUri }
                        if (index != -1) controller?.seekTo(index, seekPos)
                    }
                }
            }
        }
    }

    private fun updateLoadingProgress(current: Int, total: Int) {
        val container = findViewById<LinearLayout>(R.id.progressContainer)
        val bar = findViewById<ProgressBar>(R.id.determinateBar)
        val text = findViewById<TextView>(R.id.tvProgressText)
        val queue = findViewById<RecyclerView>(R.id.rvQueue)

        // Ensure we handle 0 total or finished state
        if (total > 0 && current < total) {
            container.visibility = android.view.View.VISIBLE
            queue.alpha = 0.2f
            bar.max = total
            bar.progress = current
            val percent = ((current.toFloat() / total.toFloat()) * 100).toInt()
            text.text = "Loading: $percent%"
        } else {
            container.visibility = android.view.View.GONE
            queue.alpha = 1.0f
        }
    }

    private fun loadPlaybackStateOnlySeek() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastUri = prefs.getString(KEY_URI, null)
        val pos = prefs.getLong(KEY_POS, 0L)

        val list = viewModel.cachedSongList ?: return
        val index = list.indexOfFirst { it.requestMetadata.mediaUri.toString() == lastUri }
        if (index != -1) {
            controller?.seekTo(index, pos)
        }
    }

    private fun handleExternalIntent(intent: Intent) {
        val uri = intent.data ?: return
        currentFolderName = "EXTERNAL_FILE"
        viewModel.cachedSongList = null

        lifecycleScope.launch(Dispatchers.IO) {
            var title: String? = null
            var artist: String? = null
            var art: ByteArray? = null

            // STEP 1: Try querying the Android MediaStore (The system database)
            // This is the best way for the URI shown in your screenshot
            try {
                val projection = arrayOf(
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST
                )
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE))
                        artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            // STEP 2: Fallback to MediaMetadataRetriever if database query failed
            // (Useful for files from Downloads folder or 3rd party apps)
            if (title == null || artist == null) {
                val mmr = MediaMetadataRetriever()
                try {
                    // Use the context-based setDataSource, it handles URIs better than FileDescriptors
                    mmr.setDataSource(this@MainActivity, uri)
                    title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    art = mmr.embeddedPicture
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    mmr.release()
                }
            }

            // STEP 3: Last resort - use the actual filename
            if (title.isNullOrEmpty()) {
                title = uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: "Unknown Song"
            }
            if (artist.isNullOrEmpty()) artist = "Unknown Artist"

            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setDisplayTitle(title)
                .setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()

            val singleItem = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setMediaMetadata(metadata)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .build()

            withContext(Dispatchers.Main) {
                val list = listOf(singleItem)
                controller?.setMediaItems(list)
                controller?.prepare()
                controller?.play()

                // Immediately force the UI to update
                updateBottomPlayerUI(singleItem)
                updateQueueUI(list)
            }
        }
    }

}

val DocumentFile.nameWithoutExtension: String get() = name?.substringBeforeLast(".") ?: ""