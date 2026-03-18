// Central state holder and playback orchestrator for the app.

package com.trec.music.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.trec.music.PlaybackService
import com.trec.music.PlaybackCoordinator
import com.trec.music.PrefsManager
import com.trec.music.data.LibraryRepository
import com.trec.music.data.LyricsRepository
import com.trec.music.data.TrecTrackEnhanced
import com.trec.music.data.api.CoverArtService
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

@OptIn(UnstableApi::class)
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    // --- DEPENDENCIES ---
    val repository = LibraryRepository(application)
    val lyricsRepository = LyricsRepository(application)
    val prefs = PrefsManager(application)

    // --- HANDLERS (Delegates) ---
    val libraryHandler = LibraryHandler(this)
    val dspHandler = DspHandler(this)
    val sensorHandler = SensorHandler(this)
    val metadataHandler = MetadataHandler(this)

    init {
        PlaybackCoordinator.registerMusicPause { pausePlayback() }
    }

    // --- STATE: PLAYER ---
    var player: Player? by mutableStateOf(null)
    var isPlaying by mutableStateOf(false)
    var currentTrackTitle by mutableStateOf("TREC MUSIC")
    var currentTrackArtist by mutableStateOf<String?>(null)
    var currentTrackAlbum by mutableStateOf<String?>(null)
    var currentTrackUri: Uri? by mutableStateOf(null)
    var playlistUpdateTrigger by mutableIntStateOf(0)

    var currentCoverUrl by mutableStateOf<String?>(null)
    var hasEmbeddedArtwork by mutableStateOf(false)

    private val coverArtService = CoverArtService()
    private val coverCache = mutableMapOf<String, String>()

    // --- LYRICS STATE ---
    var currentLyrics by mutableStateOf<String?>(null)
    var isLoadingLyrics by mutableStateOf(false)
    var lyricsError by mutableStateOf<String?>(null)
    var showLyricsDialog by mutableStateOf(false)

    // ==========================================
    // STATE: VISUALS & SETTINGS
    // ==========================================

    private val _isVinylModeEnabled = mutableStateOf(true)
    var isVinylModeEnabled: Boolean
        get() = _isVinylModeEnabled.value
        set(value) {
            _isVinylModeEnabled.value = value
            prefs.saveVinylModeEnabled(value)
        }

    var vinylRotationAngle by mutableFloatStateOf(0f)

    private val _isDynamicColorEnabled = mutableStateOf(true)
    var isDynamicColorEnabled: Boolean
        get() = _isDynamicColorEnabled.value
        set(value) {
            _isDynamicColorEnabled.value = value
            prefs.saveDynamicColorEnabled(value)
            if (!value) dominantColor = staticColor
        }

    private val _staticColor = mutableStateOf(Color(0xFFD50000))
    var staticColor: Color
        get() = _staticColor.value
        set(value) {
            _staticColor.value = value
            prefs.saveStaticColor(value.toArgb())
            if (!isDynamicColorEnabled) dominantColor = value
        }

    var dominantColor by mutableStateOf(Color(0xFFD50000))
    var secondaryColor by mutableStateOf(Color(0xFF050505))
    var isLoading by mutableStateOf(false)

    // ==========================================
    // STATE: FEATURE FLAGS (MODULES)
    // ==========================================

    // Recorder
    private val _isRecorderFeatureEnabled = mutableStateOf(true)
    var isRecorderFeatureEnabled: Boolean
        get() = _isRecorderFeatureEnabled.value
        set(value) {
            _isRecorderFeatureEnabled.value = value
            prefs.saveRecorderFeatureEnabled(value)
        }

    // Radio
    private val _isRadioEnabled = mutableStateOf(false)
    var isRadioEnabled: Boolean
        get() = _isRadioEnabled.value
        set(value) {
            _isRadioEnabled.value = value
            prefs.saveRadioFeatureEnabled(value)
        }

    // DSP Global
    private val _isDspFeatureEnabled = mutableStateOf(true)
    var isDspFeatureEnabled: Boolean
        get() = _isDspFeatureEnabled.value
        set(value) {
            _isDspFeatureEnabled.value = value
            prefs.saveDspFeatureEnabled(value)
        }

    // DSP Granular
    private val _isReverseFeatureEnabled = mutableStateOf(true)
    var isReverseFeatureEnabled: Boolean
        get() = _isReverseFeatureEnabled.value
        set(value) {
            _isReverseFeatureEnabled.value = value
            prefs.saveReverseFeatureEnabled(value)
        }

    private val _isKaraokeFeatureEnabled = mutableStateOf(true)
    var isKaraokeFeatureEnabled: Boolean
        get() = _isKaraokeFeatureEnabled.value
        set(value) {
            _isKaraokeFeatureEnabled.value = value
            prefs.saveKaraokeFeatureEnabled(value)
        }

    private val _isSpeedFeatureEnabled = mutableStateOf(true)
    var isSpeedFeatureEnabled: Boolean
        get() = _isSpeedFeatureEnabled.value
        set(value) {
            _isSpeedFeatureEnabled.value = value
            prefs.saveSpeedFeatureEnabled(value)
        }

    private val _isEffectsFeatureEnabled = mutableStateOf(true)
    var isEffectsFeatureEnabled: Boolean
        get() = _isEffectsFeatureEnabled.value
        set(value) {
            _isEffectsFeatureEnabled.value = value
            prefs.saveEffectsFeatureEnabled(value)
        }

    // ==========================================
    // STATE: ULTIMATE SETTINGS (AUDIO EXPERT)
    // ==========================================

    private fun syncSettingsToService() {
        val controller = player as? MediaController
        val command = SessionCommand(PlaybackService.CMD_UPDATE_SETTINGS, Bundle.EMPTY)
        controller?.sendCustomCommand(command, Bundle.EMPTY)
    }

    private val _skipSilenceEnabled = mutableStateOf(false)
    var skipSilenceEnabled: Boolean
        get() = _skipSilenceEnabled.value
        set(value) {
            _skipSilenceEnabled.value = value
            prefs.saveSkipSilence(value)
            syncSettingsToService() // comment normalized
        }

    private val _crossfadeMs = mutableStateOf(0)
    var crossfadeMs: Int
        get() = _crossfadeMs.value
        set(value) {
            _crossfadeMs.value = value
            prefs.saveCrossfade(value)
        }

    private val _monoAudio = mutableStateOf(false)
    var monoAudio: Boolean
        get() = _monoAudio.value
        set(value) {
            _monoAudio.value = value
            prefs.saveMonoAudio(value)
            syncSettingsToService() // comment normalized
        }

    private val _audioBalance = mutableStateOf(0f)
    var audioBalance: Float
        get() = _audioBalance.value
        set(value) {
            _audioBalance.value = value
            prefs.saveAudioBalance(value)
            syncSettingsToService() // comment normalized
        }

    // ==========================================
    // STATE: UI & BEHAVIOR
    // ==========================================

    private val _keepScreenOn = mutableStateOf(false)
    var keepScreenOn: Boolean
        get() = _keepScreenOn.value
        set(value) {
            _keepScreenOn.value = value
            prefs.saveKeepScreenOn(value)
        }

    private val _showFilename = mutableStateOf(false)
    var showFilename: Boolean
        get() = _showFilename.value
        set(value) {
            _showFilename.value = value
            prefs.saveShowFilename(value)
        }

    // Sleep Timer
    var sleepTimerRemainingFormatted by mutableStateOf<String?>(null)
    private var sleepTimerJob: Job? = null

    // --- STATE: LOGIC ---
    var isErrorState by mutableStateOf(false)
    var brokenTracks = mutableSetOf<String>()
    var lastSkipDirection = 1
    var errorAnimationJob: Job? = null
    var progressJob: Job? = null
    private var lastStateSaveAt: Long = 0L

    // --- STATE: DATA ---
    var duration by mutableLongStateOf(0L)
    var currentPosition by mutableLongStateOf(0L)
    var playlist = mutableStateListOf<TrecTrackEnhanced>()
    var favoriteTracks = mutableStateListOf<String>()
    var isCurrentTrackFav by mutableStateOf(false)
    var userPlaylists = mutableStateListOf<String>()
    var currentPlaylistFilter by mutableStateOf<String?>(null)

    // --- STATE: DSP EFFECTS ---
    var playbackSpeed by mutableFloatStateOf(1.0f)
    var playbackPitch by mutableFloatStateOf(1.0f)
    var equalizer: Equalizer? = null
    var currentPresetName by mutableStateOf("Normal")
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var shuffleMode by mutableStateOf(false)

    var isReversing by mutableStateOf(false)
    var isGeneratingReverse by mutableStateOf(false)
    var isReverseReady by mutableStateOf(false)
    var isVocalRemovalProcessing by mutableStateOf(false)
    var isInstrumentalReady by mutableStateOf(false)
    var instrumentalTrackPath by mutableStateOf<String?>(null)
    var reverseCacheSize by mutableStateOf("0 MB")

    // Internal DSP State
    var normalTrackUri: Uri? = null
    var reverseTrackPath: String? = null
    var backgroundGenJob: Job? = null

    // --- STATE: TOGGLES ---
    private val _isNeedleEnabled = mutableStateOf(true)
    var isNeedleEnabled: Boolean
        get() = _isNeedleEnabled.value
        set(value) {
            _isNeedleEnabled.value = value
            prefs.saveNeedleEnabled(value)
        }

    private val _isScratchSoundEnabled = mutableStateOf(true)
    var isScratchSoundEnabled: Boolean
        get() = _isScratchSoundEnabled.value
        set(value) {
            _isScratchSoundEnabled.value = value
            prefs.saveScratchEnabled(value)
        }

    private val _isShakeEnabled = mutableStateOf(true)
    var isShakeEnabled: Boolean
        get() = _isShakeEnabled.value
        set(value) {
            _isShakeEnabled.value = value
            prefs.saveShakeEnabled(value)
        }

    // --- INTERNALS ---
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    var audioSessionId: Int = 0
    private var preScratchSpeed: Float = 1f
    private var isScratching = false

    // ==========================================
    // INITIALIZATION
    // ==========================================

    fun initialize(context: Context? = null) {
        val app = getApplication<Application>()

        sensorHandler.initSensors(app)
        sensorHandler.initSoundPool(app)

        favoriteTracks.clear()
        favoriteTracks.addAll(repository.getFavorites())
        brokenTracks.addAll(repository.getBlacklist())
        libraryHandler.refreshPlaylists()

        _isNeedleEnabled.value = prefs.getNeedleEnabled()
        _isScratchSoundEnabled.value = prefs.getScratchEnabled()
        _isShakeEnabled.value = prefs.getShakeEnabled()

        _isDynamicColorEnabled.value = prefs.getDynamicColorEnabled()
        _staticColor.value = Color(prefs.getStaticColor())
        _isVinylModeEnabled.value = prefs.getVinylModeEnabled()

        // Feature Flags
        _isRecorderFeatureEnabled.value = prefs.getRecorderFeatureEnabled()
        _isRadioEnabled.value = prefs.getRadioFeatureEnabled()
        _isDspFeatureEnabled.value = prefs.getDspFeatureEnabled()
        _isReverseFeatureEnabled.value = prefs.getReverseFeatureEnabled()
        _isKaraokeFeatureEnabled.value = prefs.getKaraokeFeatureEnabled()
        _isSpeedFeatureEnabled.value = prefs.getSpeedFeatureEnabled()
        _isEffectsFeatureEnabled.value = prefs.getEffectsFeatureEnabled()

        // Audio Expert
        _skipSilenceEnabled.value = prefs.getSkipSilence()
        _crossfadeMs.value = prefs.getCrossfade()
        _monoAudio.value = prefs.getMonoAudio()
        _audioBalance.value = prefs.getAudioBalance()

        // UI Behavior
        _keepScreenOn.value = prefs.getKeepScreenOn()
        _showFilename.value = prefs.getShowFilename()

        if (!isDynamicColorEnabled) {
            dominantColor = staticColor
        }

        val sessionToken = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(app, sessionToken).buildAsync()

        controllerFuture?.addListener({
            try {
                if (controllerFuture?.isDone == true) {
                    val controller = controllerFuture?.get() ?: return@addListener
                    player = controller

                    syncSettingsToService()

                    setupPlayerListener(controller)

                    val extras = controller.sessionExtras
                    val sessionId = extras.getInt("AUDIO_SESSION_ID", 0)
                    if (sessionId != 0) {
                        dspHandler.setupEqualizer(sessionId)
                    }

                    metadataHandler.updateCurrentTrackInfo(app, controller.currentMediaItem)
                    libraryHandler.loadTrackCache()

                    val savedFolder = repository.getSavedFolderUri()
                    if (playlist.isNotEmpty()) {
                        restoreLastTrack()
                    }
                    viewModelScope.launch {
                        if (savedFolder != null) {
                            libraryHandler.loadFromFolder(app, savedFolder.toUri(), isAutoLoad = true)
                            if (playlist.isEmpty()) libraryHandler.loadFromMediaStore(app)
                        } else {
                            libraryHandler.loadFromMediaStore(app)
                        }
                        if (player?.currentMediaItem == null) {
                            restoreLastTrack()
                        }
                    }
                    dspHandler.calculateCacheSize(app)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(app))
    }

    // ==========================================
    // HELPERS
    // ==========================================

    fun getTrackTitle(track: TrecTrackEnhanced): String {
        return if (showFilename) {
            val path = track.uri.path
            path?.substringAfterLast('/') ?: track.title
        } else {
            track.title
        }
    }

    // ==========================================
    // DELEGATED METHODS (Handlers)
    // ==========================================

    // Library
    fun refreshPlaylists() = libraryHandler.refreshPlaylists()
    fun createPlaylist(name: String) = libraryHandler.createPlaylist(name)
    fun deletePlaylist(name: String) = libraryHandler.deletePlaylist(name)
    fun renamePlaylist(old: String, new: String) = libraryHandler.renamePlaylist(old, new)
    fun addTrackToPlaylist(name: String, uri: String) = libraryHandler.addTrackToPlaylist(name, uri)
    fun removeTrackFromPlaylist(name: String, uri: String) = libraryHandler.removeTrackFromPlaylist(name, uri)
    fun moveTrackInPlaylist(playlistName: String, fromIndex: Int, toIndex: Int) = libraryHandler.moveTrackInPlaylist(playlistName, fromIndex, toIndex)
    fun movePlaylist(fromIndex: Int, toIndex: Int) = libraryHandler.movePlaylist(fromIndex, toIndex)
    fun getPlaylistTracks(name: String) = libraryHandler.getPlaylistTracks(name)
    fun deleteFileFromDevice(context: Context, track: TrecTrackEnhanced) = libraryHandler.deleteFileFromDevice(context, track)
    fun refreshLibrary(context: Context) = libraryHandler.refreshLibrary(context)
    fun loadFromFolder(context: Context, folderUri: Uri, isAutoLoad: Boolean = false) = viewModelScope.launch { libraryHandler.loadFromFolder(context, folderUri, isAutoLoad) }

    // DSP & Effects
    fun applyPreset(name: String) = dspHandler.applyPreset(name)
    fun setSpeed(speed: Float) = dspHandler.setSpeed(speed)
    fun setPitch(pitch: Float) = dspHandler.setPitch(pitch)
    fun enableNightcore() = applyPreset("Nightcore")
    fun enableVaporwave() = applyPreset("Vaporwave")
    fun enableSlowedReverb() = applyPreset("Slowed + Reverb")
    fun enableChipmunk() = applyPreset("Chipmunk")
    fun enableRetro() = applyPreset("Retro")
    fun resetEffects() = applyPreset("Normal")
    fun toggleVocalRemover(context: Context) = dspHandler.toggleVocalRemover(context)
    fun toggleReverse(context: Context) = dspHandler.toggleReverse(context)
    fun clearReverseCache(context: Context) = dspHandler.clearReverseCache(context)

    // Metadata
    fun getTrackMetadataForUri(context: Context, uri: Uri) = metadataHandler.getTrackMetadataForUri(context, uri)
    fun getTrackMetadata(context: Context) = metadataHandler.getTrackMetadata(context)

    // Sensors & Toggles
    fun toggleNeedle() { isNeedleEnabled = !isNeedleEnabled }
    fun toggleScratchSound() { isScratchSoundEnabled = !isScratchSoundEnabled }
    fun toggleShake() { isShakeEnabled = !isShakeEnabled }
    fun performHapticFeedback() = sensorHandler.performHapticFeedback()
    fun startScratchLoop() = sensorHandler.startScratchLoop()
    fun stopScratchLoop() = sensorHandler.stopScratchLoop()
    fun onScratch(dragAmount: Float) = sensorHandler.onScratch(dragAmount)

    // ==========================================
    // SLEEP TIMER LOGIC
    // ==========================================

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerJob = viewModelScope.launch {
            val totalMillis = minutes * 60 * 1000L
            val startTime = System.currentTimeMillis()
            val endTime = startTime + totalMillis

            while (isActive) {
                val remaining = endTime - System.currentTimeMillis()
                if (remaining <= 0) break

                val mins = remaining / 1000 / 60
                val secs = (remaining / 1000) % 60
                sleepTimerRemainingFormatted = String.format("%02d:%02d", mins, secs)

                if (remaining < 30_000) {
                    val volume = (remaining.toFloat() / 30_000f).coerceIn(0f, 1f)
                    player?.volume = volume
                }

                delay(1000)
            }

            player?.pause()
            player?.volume = 1.0f // comment normalized
            sleepTimerRemainingFormatted = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerRemainingFormatted = null
        player?.volume = 1.0f
    }

    // ==========================================
    // VINYL SCRUBBING LOGIC
    // ==========================================

    fun onScratchStart() {
        isScratching = true
        preScratchSpeed = player?.playbackParameters?.speed ?: 1f
        sensorHandler.startScratchLoop()
    }

    fun performVinylScrub(angleDelta: Float) {
        val player = player ?: return
        val sensitivity = 40f
        val velocity = angleDelta * sensitivity

        if (abs(velocity) < 0.1f) {
            if (player.isPlaying) player.pause()
        } else {
            if (!player.isPlaying) player.play()
            val targetSpeed = abs(velocity).coerceIn(0.1f, 4.0f)

            if (velocity < 0) {
                // Движение назад
                if (isReverseReady && reverseTrackPath != null) {
                    if (!isReversing) switchPlayerSource(toReverse = true)
                    setScratchParameters(targetSpeed)
                } else {
                    val seekDelta = (targetSpeed * 50).toLong()
                    val newPos = (currentPosition - seekDelta).coerceAtLeast(0)
                    currentPosition = newPos
                    player.seekTo(newPos)
                }
            } else {
                if (isReversing) switchPlayerSource(toReverse = false)
                
                val seekDelta = (targetSpeed * 50).toLong()
                val newPos = (currentPosition + seekDelta).coerceAtMost(duration)
                currentPosition = newPos
                player.seekTo(newPos)
                
                setScratchParameters(targetSpeed)
            }
        }

        val rotationDeltaDegrees = angleDelta * (180f / Math.PI.toFloat())
        vinylRotationAngle += rotationDeltaDegrees
    }

    fun onScratchEnd() {
        isScratching = false
        val player = player ?: return
        sensorHandler.stopScratchLoop()

        if (isReversing && isReverseReady) switchPlayerSource(toReverse = false)

        val normalParams = PlaybackParameters(preScratchSpeed, 1f)
        player.playbackParameters = normalParams
        player.play()
    }

    private fun switchPlayerSource(toReverse: Boolean) {
        val player = player ?: return
        val currentPos = player.currentPosition
        val trackDuration = duration.coerceAtLeast(1)
        val targetUri: Uri?
        val targetPos: Long

        if (toReverse) {
            targetUri = Uri.parse(reverseTrackPath)
            targetPos = (trackDuration - currentPos).coerceIn(0, trackDuration)
            isReversing = true
        } else {
            targetUri = normalTrackUri ?: currentTrackUri
            targetPos = (trackDuration - currentPos).coerceIn(0, trackDuration)
            isReversing = false
        }

        if (targetUri != null) {
            val item = MediaItem.fromUri(targetUri)
            player.setMediaItem(item)
            player.prepare()
            player.seekTo(targetPos)
        }
    }

    private fun setScratchParameters(speed: Float) {
        val params = PlaybackParameters(speed, speed)
        player?.playbackParameters = params
    }

    // ==========================================
    // PLAYBACK LOGIC
    // ==========================================

    fun playTrackFromPlaylist(playlistName: String, index: Int) {
        PlaybackCoordinator.pauseRecorder()
        val tracksToPlay = if (playlistName == "All Tracks") playlist.toList() else getPlaylistTracks(playlistName)

        if (index in tracksToPlay.indices) {
            currentPlaylistFilter = if (playlistName == "All Tracks") null else playlistName
            isReversing = false; isGeneratingReverse = false; instrumentalTrackPath = null
            backgroundGenJob?.cancel()
            applyPreset("Normal")

            val mediaItems = tracksToPlay.map {
                val metadata = androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(it.title)
                    .setArtist(it.artist)
                    .setAlbumTitle(it.album)
                    .build()

                MediaItem.Builder()
                    .setMediaId(it.uri.toString())
                    .setUri(it.uri)
                    .setMediaMetadata(metadata)
                    .build()
            }
            player?.setMediaItems(mediaItems)
            player?.seekTo(index, 0)
            player?.prepare()
            player?.play()

            currentTrackUri = tracksToPlay[index].uri
            normalTrackUri = tracksToPlay[index].uri
            currentTrackTitle = tracksToPlay[index].title
            currentCoverUrl = null
            hasEmbeddedArtwork = false
            currentTrackArtist = tracksToPlay[index].artist
            currentTrackAlbum = tracksToPlay[index].album
            refreshCoverArt(currentTrackArtist, currentTrackTitle, currentTrackAlbum)
        }
    }

    fun playTrackAtIndex(index: Int) = playTrackFromPlaylist("All Tracks", index)

    fun playShuffledFromPlaylist(playlistName: String) {
        PlaybackCoordinator.pauseRecorder()
        val tracksToPlay = if (playlistName == "All Tracks") playlist.toList() else getPlaylistTracks(playlistName)
        if (tracksToPlay.isEmpty()) return

        currentPlaylistFilter = if (playlistName == "All Tracks") null else playlistName
        isReversing = false
        isGeneratingReverse = false
        instrumentalTrackPath = null
        backgroundGenJob?.cancel()
        applyPreset("Normal")

        val mediaItems = tracksToPlay.map {
            val metadata = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(it.title)
                .setArtist(it.artist)
                .setAlbumTitle(it.album)
                .build()

            MediaItem.Builder()
                .setMediaId(it.uri.toString())
                .setUri(it.uri)
                .setMediaMetadata(metadata)
                .build()
        }
        val randomIndex = Random.nextInt(tracksToPlay.size)
        player?.setMediaItems(mediaItems)
        player?.shuffleModeEnabled = true
        shuffleMode = true
        player?.seekTo(randomIndex, 0)
        player?.prepare()
        player?.play()

        currentTrackUri = tracksToPlay[randomIndex].uri
        normalTrackUri = tracksToPlay[randomIndex].uri
        currentTrackTitle = tracksToPlay[randomIndex].title
        currentCoverUrl = null
        hasEmbeddedArtwork = false
        currentTrackArtist = tracksToPlay[randomIndex].artist
        currentTrackAlbum = tracksToPlay[randomIndex].album
        refreshCoverArt(currentTrackArtist, currentTrackTitle, currentTrackAlbum)
    }

    fun togglePlay() {
        if (isPlaying) {
            player?.pause()
            saveState()
        } else {
            PlaybackCoordinator.pauseRecorder()
            player?.play()
        }
    }

    fun pausePlayback() {
        if (isPlaying) {
            player?.pause()
            saveState()
        }
    }

    fun skipNext() {
        if (isErrorState) { errorAnimationJob?.cancel(); isErrorState = false }
        lastSkipDirection = 1
        if (isReversing || instrumentalTrackPath != null) {
            handleCustomTrackSkip(1)
        } else {
            if (player?.hasNextMediaItem() == true) player?.seekToNext()
            else if (shuffleMode) player?.seekToNextMediaItem()
        }
        player?.play()
    }

    fun skipPrev() {
        if (isErrorState) { errorAnimationJob?.cancel(); isErrorState = false }
        lastSkipDirection = -1
        if (isReversing || instrumentalTrackPath != null) {
            handleCustomTrackSkip(-1)
        } else {
            player?.seekToPrevious()
        }
        player?.play()
    }

    private fun handleCustomTrackSkip(direction: Int) {
        val tracks = if (currentPlaylistFilter != null) libraryHandler.getPlaylistTracks(currentPlaylistFilter!!) else playlist.toList()
        val curUri = normalTrackUri ?: currentTrackUri
        val idx = tracks.indexOfFirst { it.uri == curUri }
        if (idx != -1) {
            var nextIdx = idx + direction
            if (nextIdx >= tracks.size) nextIdx = 0
            if (nextIdx < 0) nextIdx = tracks.size - 1
            playTrackFromPlaylist(currentPlaylistFilter ?: "All Tracks", nextIdx)
        }
    }

    fun skipNextRandom() {
        performHapticFeedback()
        if (!shuffleMode) toggleShuffle()
        skipNext()
    }

    fun seekTo(pos: Long) {
        if (isReversing && duration > 0) player?.seekTo(duration - pos) else player?.seekTo(pos)
        currentPosition = pos
    }

    fun toggleShuffle() { shuffleMode = !shuffleMode; player?.shuffleModeEnabled = shuffleMode }

    fun cycleRepeatMode() {
        repeatMode = if (repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else if (repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player?.repeatMode = repeatMode
    }

    fun playExternalFile(context: Context, file: File) {
        PlaybackCoordinator.pauseRecorder()
        isReversing = false; instrumentalTrackPath = null
        val uri = Uri.fromFile(file)
        currentTrackTitle = file.name
        currentTrackUri = uri
        currentCoverUrl = null
        hasEmbeddedArtwork = false
        currentTrackArtist = null
        currentTrackAlbum = null

        viewModelScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                withContext(Dispatchers.Main) {
                    if (!title.isNullOrBlank()) currentTrackTitle = title
                    currentTrackArtist = artist
                    currentTrackAlbum = album
                }
                refreshCoverArt(artist, title ?: currentTrackTitle, album)
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        if (isDynamicColorEnabled) {
            dominantColor = Color(0xFFD50000); secondaryColor = Color.Black
        } else {
            dominantColor = staticColor
            secondaryColor = Color.Black
        }

        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(file.name).build())
            .build()

        player?.setMediaItem(item)
        player?.prepare()
        player?.play()
    }

    fun saveState() { currentTrackUri?.toString()?.let { repository.saveLastState(it, currentPosition) } }

    // --- LYRICS METHODS ---
    fun loadLyrics() {
        isLoadingLyrics = true
        lyricsError = null
        currentLyrics = null

        viewModelScope.launch {
            val uriString = currentTrackUri?.toString()
            val track = playlist.find { it.uri.toString() == uriString }
                ?: playlist.find { it.title == currentTrackTitle }

            val artist = track?.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val title = track?.title?.takeIf { it.isNotBlank() }
                ?: currentTrackTitle.takeIf { it.isNotBlank() && it != "TREC MUSIC" }

            if (title == null) {
                lyricsError = "Track title is unavailable"
                isLoadingLyrics = false
                return@launch
            }

            val result = lyricsRepository.getLyrics(artist, title)
            result.onSuccess { data ->
                currentLyrics = data.lyrics
                isLoadingLyrics = false
            }.onFailure { error ->
                lyricsError = error.message
                isLoadingLyrics = false
            }
        }
    }

    fun clearLyrics() {
        currentLyrics = null
        lyricsError = null
        isLoadingLyrics = false
    }
    
    fun refreshCoverArt(artist: String?, title: String?, album: String?) {
        if (hasEmbeddedArtwork) return
        val safeTitle = title?.trim().orEmpty()
        if (safeTitle.isBlank()) return

        val cacheKey = listOf(artist?.trim().orEmpty(), safeTitle, album?.trim().orEmpty()).joinToString("|")
        coverCache[cacheKey]?.let { cached ->
            currentCoverUrl = cached
            return
        }

        val activeUri = currentTrackUri?.toString()
        viewModelScope.launch(Dispatchers.IO) {
            val url = coverArtService.fetchCoverUrl(artist, safeTitle, album)
            if (!url.isNullOrBlank()) {
                coverCache[cacheKey] = url
                withContext(Dispatchers.Main) {
                    if (!hasEmbeddedArtwork && currentTrackUri?.toString() == activeUri) {
                        currentCoverUrl = url
                    }
                }
            }
        }
    }

    fun restoreLastTrack() {
        val lastUri = repository.getLastTrackUri() ?: return
        val player = player ?: return
        val index = playlist.indexOfFirst { it.uri.toString() == lastUri }
        if (index != -1) {
            val lastPosition = repository.getLastTrackPos().coerceAtLeast(0L)
            val mediaItems = playlist.map {
                val metadata = androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(it.title)
                    .setArtist(it.artist)
                    .setAlbumTitle(it.album)
                    .build()

                MediaItem.Builder()
                    .setMediaId(it.uri.toString())
                    .setUri(it.uri)
                    .setMediaMetadata(metadata)
                    .build()
            }
            player.setMediaItems(mediaItems, index, lastPosition)
            player.prepare()
            player.playWhenReady = false

            val track = playlist[index]
            currentTrackUri = track.uri
            normalTrackUri = track.uri
            currentTrackTitle = track.title
            currentCoverUrl = null
            hasEmbeddedArtwork = false
            currentTrackArtist = track.artist
            currentTrackAlbum = track.album
            refreshCoverArt(currentTrackArtist, currentTrackTitle, currentTrackAlbum)
            currentPosition = lastPosition
        }
    }
    fun getAllTracks(): List<TrecTrackEnhanced> = playlist

    fun getFilteredPlaylist(): List<TrecTrackEnhanced> {
        if (currentPlaylistFilter == null) return playlist
        val allowedUris = repository.getTracksInPlaylist(currentPlaylistFilter!!)
        return playlist.filter { allowedUris.contains(it.uri.toString()) }
    }

    fun stopAndClear() {
        player?.pause()
        player?.clearMediaItems()
        isPlaying = false
        currentTrackUri = null
        currentTrackTitle = "TREC MUSIC"
        currentTrackArtist = null
        currentTrackAlbum = null
        instrumentalTrackPath = null
        isReversing = false
    }
    fun toggleFavorite() {
        val uriStr = currentTrackUri.toString()
        val favs = repository.getFavorites().toMutableSet()
        if (favs.contains(uriStr)) {
            favs.remove(uriStr)
            isCurrentTrackFav = false
            favoriteTracks.remove(uriStr)
        } else {
            favs.add(uriStr)
            isCurrentTrackFav = true
            favoriteTracks.add(uriStr)
        }
        repository.saveFavorites(favs)
    }

    fun openSystemEqualizer(context: Context) {
        try {
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Equalizer is not available", Toast.LENGTH_SHORT).show()
        }
    }

    // ==========================================
    // LISTENERS & UPDATERS
    // ==========================================

    private fun setupPlayerListener(p: Player) {
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing && !isErrorState) startProgressUpdater()
            }
            override fun onMediaItemTransition(mi: MediaItem?, r: Int) {
                sensorHandler.stopScratchLoop(); isErrorState = false
                if (isScratching) return

                if (mi?.mediaId != reverseTrackPath && mi?.mediaId != instrumentalTrackPath) {
                    if (isReversing) isReversing = false
                    normalTrackUri = null
                    metadataHandler.updateCurrentTrackInfo(getApplication(), mi)
                    saveState()
                }
            }
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY) duration = p.duration.coerceAtLeast(0)
                if (s == Player.STATE_ENDED && !isErrorState) lastSkipDirection = 1
            }
            override fun onPlayerError(e: PlaybackException) {
                if (!isErrorState) { isErrorState = true; runErrorAnimation() }
            }
        })
    }

    private fun startProgressUpdater() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive && (isPlaying || isErrorState)) {
                if (!isScratching) {
                    val real = player?.currentPosition ?: 0L
                    if (!isErrorState) currentPosition = if (isReversing) (duration - real).coerceAtLeast(0) else real

                    if (isVinylModeEnabled) {
                        if (isErrorState) vinylRotationAngle += 30f
                        else if (player?.isLoading == false) {
                            val dir = if (isReversing) -1.0f else 1.0f
                            val speed = player?.playbackParameters?.speed ?: 1f
                            vinylRotationAngle += 1.5f * dir * speed
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (isPlaying && now - lastStateSaveAt >= 5000L) {
                    saveState()
                    lastStateSaveAt = now
                }
                delay(120L)
            }
        }
    }

    private fun runErrorAnimation() {
        errorAnimationJob?.cancel()
        errorAnimationJob = viewModelScope.launch {
            val fakeDuration = 180_000L; duration = fakeDuration
            for (i in 0..30) {
                if (!isActive) return@launch
                currentPosition = (fakeDuration * (i.toFloat() / 30)).toLong()
                if (isVinylModeEnabled) vinylRotationAngle += 45f
                if (i % 3 == 0 && isScratchSoundEnabled && sensorHandler.hasScratchSounds()) sensorHandler.playRandomScratch()
                delay(100L)
            }
            if (lastSkipDirection == 1) skipNext() else skipPrev()
            isErrorState = false
        }
    }

    override fun onCleared() {
        PlaybackCoordinator.clearMusic()
        saveState()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        player = null
        sensorHandler.cleanup()
        super.onCleared()
    }
}



















