// Central state holder and playback orchestrator for the app.

package com.trec.music.viewmodel

import android.Manifest
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.request.ImageRequest
import com.trec.music.PlaybackService
import com.trec.music.PlaybackCoordinator
import com.trec.music.PrefsManager
import com.trec.music.SavedPlaybackState
import com.trec.music.data.LibraryRepository
import com.trec.music.data.LyricsRepository
import com.trec.music.data.TrecTrackEnhanced
import com.trec.music.data.api.CoverArtService
import com.trec.music.data.api.RemoteTrackMetadata
import com.trec.music.data.api.TrackMetadataService
import com.trec.music.utils.AudioAnalysisBus
import com.trec.music.utils.AudioAnalysisFrame
import com.trec.music.utils.chooseTrecAccentColor
import com.trec.music.utils.TrackMetadataText
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

enum class KaraokeOutputMode {
    INSTRUMENTAL,
    ACAPELLA
}

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
    var audioAnalysis by mutableStateOf(AudioAnalysisFrame.Silent)
        private set

    var currentCoverUrl by mutableStateOf<String?>(null)
    var hasEmbeddedArtwork by mutableStateOf(false)

    private val coverArtService = CoverArtService()
    private val trackMetadataService = TrackMetadataService()
    private val coverUrlCache = mutableStateMapOf<String, String>()
    private val coverColorCache = mutableStateMapOf<String, Color>()
    private val metadataCache = java.util.concurrent.ConcurrentHashMap<String, RemoteTrackMetadata>()
    // ФИКС КРАША: ConcurrentHashMap безопасен для многопоточного добавления/удаления (Main Thread + Dispatchers.IO)
    private val coverFetchJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val metadataFetchJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val coverFetchLimiter = Semaphore(4)
    private val metadataFetchLimiter = Semaphore(3)
    private var metadataPersistJob: Job? = null
    private var appliedArtworkSignature: String? = null

    private fun hasNotificationPermission(): Boolean {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // Android 13+: если пользователь запретил уведомления, попытка запустить MediaSessionService в FG
    // может падать (SecurityException). Вместо краша — попросим разрешение по требованию.
    var requestNotificationPermission by mutableStateOf(false)
        private set
    private var pendingNotificationAction: (() -> Unit)? = null

    private fun runWithNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission()) {
            action()
            return
        }
        pendingNotificationAction = action
        requestNotificationPermission = true
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        requestNotificationPermission = false
        val action = pendingNotificationAction
        pendingNotificationAction = null
        if (granted && action != null) {
            viewModelScope.launch(Dispatchers.Main) { action() }
        } else if (!granted && action != null) {
            Toast.makeText(getApplication(), "Для фонового воспроизведения нужны уведомления", Toast.LENGTH_LONG).show()
        }
    }

    // --- LYRICS STATE ---
    var currentLyrics by mutableStateOf<String?>(null)
    var isLoadingLyrics by mutableStateOf(false)
    var lyricsError by mutableStateOf<String?>(null)
    var showLyricsDialog by mutableStateOf(false)
    private var lyricsJob: Job? = null  // ← добавили

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
            if (!value) {
                dominantColor = staticColor
                secondaryColor = Color(0xFF050505)
            }
        }

    private val _staticColor = mutableStateOf(Color(0xFFD50000))
    var staticColor: Color
        get() = _staticColor.value
        set(value) {
            _staticColor.value = value
            prefs.saveStaticColor(value.toArgb())
            if (!isDynamicColorEnabled) {
                dominantColor = value
                secondaryColor = Color(0xFF050505)
            }
        }

    private val _useSpectrumColorPicker = mutableStateOf(false)
    var useSpectrumColorPicker: Boolean
        get() = _useSpectrumColorPicker.value
        set(value) {
            _useSpectrumColorPicker.value = value
            prefs.saveSpectrumColorPickerEnabled(value)
        }

    var dominantColor by mutableStateOf(Color(0xFFD50000))
    var secondaryColor by mutableStateOf(Color(0xFF050505))
    var isLoading by mutableStateOf(false)

    // ==========================================
    // STATE: FEATURE FLAGS (MODULES)
    // ==========================================

    // Recorder
    private val _isRecorderFeatureEnabled = mutableStateOf(false)
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

    // Speed behavior: when enabled, pitch will follow speed (slower = deeper voice).
    private val _isPitchFollowsSpeed = mutableStateOf(true)
    var isPitchFollowsSpeed: Boolean
        get() = _isPitchFollowsSpeed.value
        set(value) {
            _isPitchFollowsSpeed.value = value
            prefs.savePitchFollowsSpeed(value)
            // Apply current speed again so the change is audible immediately.
            dspHandler.setSpeed(playbackSpeed)
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

    private val _crossfadeMs = mutableIntStateOf(0)
    var crossfadeMs: Int
        get() = _crossfadeMs.intValue
        set(value) {
            _crossfadeMs.intValue = value
            prefs.saveCrossfade(value)
        }

    // Время начала fade-in (для кроссфейда) в elapsedRealtime, -1 = не активно.
    @Volatile private var crossfadeFadeInStartElapsed: Long = -1L

    private val _monoAudio = mutableStateOf(false)
    var monoAudio: Boolean
        get() = _monoAudio.value
        set(value) {
            _monoAudio.value = value
            prefs.saveMonoAudio(value)
            syncSettingsToService() // comment normalized
        }

    private val _audioBalance = mutableFloatStateOf(0f)
    var audioBalance: Float
        get() = _audioBalance.floatValue
        set(value) {
            _audioBalance.floatValue = value
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
    @Volatile private var sleepVolumeFactor: Float = 1f

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
    var archivedTracks = mutableStateListOf<TrecTrackEnhanced>()
    var favoriteTracks = mutableStateListOf<String>()
    var isCurrentTrackFav by mutableStateOf(false)
    var userPlaylists = mutableStateListOf<String>()
    var currentPlaylistFilter by mutableStateOf<String?>(null)
    var favoritesContext: List<TrecTrackEnhanced>? = null  // ← добавить
    var archiveContext: List<TrecTrackEnhanced>? = null

    fun playlistSnapshot(): List<TrecTrackEnhanced> = copyStateListSafely(playlist)

    fun archivedTracksSnapshot(): List<TrecTrackEnhanced> = copyStateListSafely(archivedTracks)

    fun archivedTrackUrisSnapshot(): Set<String> {
        return archivedTracksSnapshot().map { it.uri.toString() }.toSet()
    }

    fun favoriteTracksSnapshot(): Set<String> = copyStateListSafely(favoriteTracks).toSet()

    private fun <T> copyStateListSafely(source: List<T>): List<T> {
        repeat(3) {
            try {
                return source.toList()
            } catch (_: IndexOutOfBoundsException) {
            } catch (_: ConcurrentModificationException) {
            }
        }
        return emptyList()
    }

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
    var vocalRemovalProcessingUri by mutableStateOf<String?>(null)
    var isInstrumentalReady by mutableStateOf(false)
    var instrumentalTrackPath by mutableStateOf<String?>(null)
    var karaokeOutputMode by mutableStateOf(KaraokeOutputMode.INSTRUMENTAL)
    var karaokeRemovalStrength by mutableFloatStateOf(1.0f)
    var karaokeVocalBoost by mutableFloatStateOf(1.15f)
    var reverseCacheSize by mutableStateOf("0 MB")
    var appCacheSize by mutableStateOf("0 MB")

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
    private var preScratchParams: PlaybackParameters = PlaybackParameters.DEFAULT
    private var isScratching = false
    private val vinylTickIntervalMs = 120f
    private val vinylBaseRpm = 26.5f

    // ==========================================
    // INITIALIZATION
    // ==========================================

    fun initialize(context: Context? = null) {
        val app = getApplication<Application>()

        sensorHandler.initSensors(app)
        sensorHandler.initSoundPool(app)

        favoriteTracks.clear()
        favoriteTracks.addAll(repository.getFavorites())
        archivedTracks.clear()
        archivedTracks.addAll(repository.getArchivedTracks())
        brokenTracks.addAll(repository.getBlacklist())
        libraryHandler.refreshPlaylists()

        _isNeedleEnabled.value = prefs.getNeedleEnabled()
        _isScratchSoundEnabled.value = prefs.getScratchEnabled()
        _isShakeEnabled.value = prefs.getShakeEnabled()

        _isDynamicColorEnabled.value = prefs.getDynamicColorEnabled()
        _staticColor.value = Color(prefs.getStaticColor())
        _useSpectrumColorPicker.value = prefs.getSpectrumColorPickerEnabled()
        _isVinylModeEnabled.value = prefs.getVinylModeEnabled()

        // Feature Flags
        _isRecorderFeatureEnabled.value = prefs.getRecorderFeatureEnabled()
        _isRadioEnabled.value = prefs.getRadioFeatureEnabled()
        _isDspFeatureEnabled.value = prefs.getDspFeatureEnabled()
        _isReverseFeatureEnabled.value = prefs.getReverseFeatureEnabled()
        _isKaraokeFeatureEnabled.value = prefs.getKaraokeFeatureEnabled()
        _isSpeedFeatureEnabled.value = prefs.getSpeedFeatureEnabled()
        _isPitchFollowsSpeed.value = prefs.getPitchFollowsSpeed()
        _isEffectsFeatureEnabled.value = prefs.getEffectsFeatureEnabled()
        karaokeOutputMode = runCatching {
            KaraokeOutputMode.valueOf(prefs.getKaraokeOutputMode())
        }.getOrDefault(KaraokeOutputMode.INSTRUMENTAL)
        karaokeRemovalStrength = prefs.getKaraokeRemovalStrength().coerceIn(0f, 1.5f)
        karaokeVocalBoost = prefs.getKaraokeVocalBoost().coerceIn(1f, 2f)

        // Audio Expert
        _skipSilenceEnabled.value = prefs.getSkipSilence()
        _crossfadeMs.intValue = prefs.getCrossfade()
        _monoAudio.value = prefs.getMonoAudio()
        _audioBalance.floatValue = prefs.getAudioBalance()

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
                    if (controller.currentMediaItem != null) {
                        syncNowPlayingFromPlayer()
                    } else {
                        restoreLastTrack()
                    }

                    val savedFolder = repository.getSavedFolderUri()
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
                    refreshAppCacheSize(app)
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

    fun getTrackArtist(track: TrecTrackEnhanced?): String {
        if (track == null) return "Unknown Artist"
        return TrackMetadataText.displayArtist(track.artist, track.albumArtist, track.title)
    }

    fun getCurrentDisplayArtist(): String {
        if (currentTrackUri == null) return "—"
        val track = playlistSnapshot().find { it.uri == currentTrackUri }
            ?: archivedTracksSnapshot().find { it.uri == currentTrackUri }
        return TrackMetadataText.displayArtist(currentTrackArtist ?: track?.artist, track?.albumArtist, currentTrackTitle)
    }

    fun currentPlaybackTrack(): TrecTrackEnhanced? {
        val uri = currentTrackUri ?: return null
        val uriString = uri.toString()
        return playlistSnapshot().find { it.uri.toString() == uriString }
            ?: archivedTracksSnapshot().find { it.uri.toString() == uriString }
            ?: repository.getPlaybackState()?.queue?.find { it.uri.toString() == uriString }
            ?: TrecTrackEnhanced(
                uri = uri,
                title = currentTrackTitle.takeIf { it.isNotBlank() && it != "TREC MUSIC" }
                    ?: uri.lastPathSegment?.substringBeforeLast('.')
                    ?: "TREC MUSIC",
                artist = currentTrackArtist,
                album = currentTrackAlbum,
                durationMs = duration
            )
    }

    fun isKaraokeProcessingForCurrentTrack(): Boolean {
        val currentUri = currentTrackUri?.toString()
        return isVocalRemovalProcessing && currentUri != null && currentUri == vocalRemovalProcessingUri
    }

    fun getKaraokeCacheFileFor(uri: Uri, context: Context): File {
        val modeTag = if (karaokeOutputMode == KaraokeOutputMode.ACAPELLA) "acapella" else "inst"
        val strengthTag = (karaokeRemovalStrength * 100f).roundToInt().coerceIn(0, 150)
        val boostTag = (karaokeVocalBoost * 100f).roundToInt().coerceIn(100, 200)
        val trackHash = uri.toString().hashCode()
        return File(context.cacheDir, "sep_umxl1_${modeTag}_s${strengthTag}_b${boostTag}_${trackHash}.wav")
    }

    fun updateKaraokeOutputMode(mode: KaraokeOutputMode) {
        karaokeOutputMode = mode
        prefs.saveKaraokeOutputMode(mode.name)
        refreshKaraokeReadyStateForCurrentTrack()
    }

    fun updateKaraokeRemovalStrength(value: Float) {
        val clamped = value.coerceIn(0f, 1.5f)
        karaokeRemovalStrength = clamped
        prefs.saveKaraokeRemovalStrength(clamped)
        refreshKaraokeReadyStateForCurrentTrack()
    }

    fun updateKaraokeVocalBoost(value: Float) {
        val clamped = value.coerceIn(1f, 2f)
        karaokeVocalBoost = clamped
        prefs.saveKaraokeVocalBoost(clamped)
        refreshKaraokeReadyStateForCurrentTrack()
    }

    private fun refreshKaraokeReadyStateForCurrentTrack() {
        val uri = normalTrackUri ?: currentTrackUri ?: return
        val file = getKaraokeCacheFileFor(uri, getApplication())
        isInstrumentalReady = file.exists() && file.length() > 1000
    }

    fun markCurrentTrackAsPlaybackTarget(uri: Uri) {
        currentTrackUri = uri
        normalTrackUri = uri
        if (vocalRemovalProcessingUri != uri.toString()) {
            isVocalRemovalProcessing = false
        }
    }

    fun ensureMetadataForTrack(track: TrecTrackEnhanced) {
        ensureTrackMetadataForTrack(
            trackUri = track.uri.toString(),
            artist = track.artist,
            title = track.title,
            album = track.album,
            activeUri = null
        )
    }

    fun ensureCurrentTrackMetadata() {
        val activeUri = currentTrackUri?.toString() ?: return
        val current = playlistSnapshot().find { it.uri.toString() == activeUri }
        ensureTrackMetadataForTrack(
            trackUri = activeUri,
            artist = currentTrackArtist ?: current?.artist,
            title = currentTrackTitle.takeIf { it.isNotBlank() && it != "TREC MUSIC" } ?: current?.title,
            album = currentTrackAlbum ?: current?.album,
            activeUri = activeUri
        )
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
    fun persistPlaylistOrder() = libraryHandler.persistPlaylistOrder()
    fun getPlaylistTracks(name: String) = libraryHandler.getPlaylistTracks(name)
    fun deleteFileFromDevice(context: Context, track: TrecTrackEnhanced) = libraryHandler.deleteFileFromDevice(context, track)
    fun deleteFilesFromDevice(context: Context, tracks: List<TrecTrackEnhanced>) = libraryHandler.deleteFilesFromDevice(context, tracks)
    fun archiveTrack(context: Context, track: TrecTrackEnhanced) = libraryHandler.archiveTrack(context, track)
    fun archiveTracks(context: Context, tracks: List<TrecTrackEnhanced>) = libraryHandler.archiveTracks(context, tracks)
    fun restoreArchivedTrack(context: Context, track: TrecTrackEnhanced) = libraryHandler.restoreArchivedTrack(context, track)
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
                sleepTimerRemainingFormatted = String.format(Locale.US, "%02d:%02d", mins, secs)

                if (remaining < 30_000) {
                    val volume = (remaining.toFloat() / 30_000f).coerceIn(0f, 1f)
                    sleepVolumeFactor = volume
                }

                delay(1000)
            }

            player?.pause()
            sleepVolumeFactor = 1.0f
            player?.volume = 1.0f // comment normalized
            sleepTimerRemainingFormatted = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerRemainingFormatted = null
        sleepVolumeFactor = 1.0f
        player?.volume = 1.0f
    }

    // ==========================================
    // VINYL SCRUBBING LOGIC
    // ==========================================

    fun onScratchStart() {
        isScratching = true
        preScratchParams = player?.playbackParameters ?: PlaybackParameters.DEFAULT
        sensorHandler.startScratchLoop()
    }

    fun performVinylScrub(angleDelta: Float) {
        val player = player ?: return
        val sensitivity = 40f
        val velocity = angleDelta * sensitivity

        if (abs(velocity) < 0.1f) {
            if (player.isPlaying) player.pause()
        } else {
            if (!player.isPlaying) {
                runWithNotificationPermission { player.play() }
            }
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

        player.playbackParameters = preScratchParams
        runWithNotificationPermission { player.play() }
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
    private fun buildSafeMediaItem(track: TrecTrackEnhanced): MediaItem {
        val key = coverCacheKey(track.artist, track.title, track.album)
        // Берём URL из памяти, или из постоянного кэша если в памяти ещё нет
        val cachedUrl = coverUrlCache[key] ?: prefs.getCachedCoverUrl(key)

        val metaBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(getTrackArtist(track))
            .setAlbumTitle(track.album)

        if (!cachedUrl.isNullOrBlank()) {
            // URL уже в кэше → вшиваем сразу, replaceMediaItem не нужен
            metaBuilder.setArtworkUri(cachedUrl.toUri())
        }
        // Если URL нет — встроенную обложку Media3 достанет из файла сам

        return MediaItem.Builder()
            .setMediaId(track.uri.toString())
            .setUri(track.uri)
            .setMediaMetadata(metaBuilder.build())
            .build()
    }

    private fun mediaItemToTrack(mediaItem: MediaItem, fallbackDurationMs: Long = 0L): TrecTrackEnhanced {
        val uri = mediaItem.localConfiguration?.uri ?: Uri.parse(mediaItem.mediaId)
        val metadata = mediaItem.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "TREC MUSIC"
        return TrecTrackEnhanced(
            uri = uri,
            title = title,
            artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() },
            album = metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() },
            durationMs = fallbackDurationMs.coerceAtLeast(0L)
        )
    }

    private fun orderedMediaItemIndices(p: Player): List<Int> {
        val count = p.mediaItemCount
        if (count <= 0) return emptyList()
        if (!p.shuffleModeEnabled) return (0 until count).toList()

        val timeline = p.currentTimeline
        if (timeline.isEmpty) return (0 until count).toList()

        val result = ArrayList<Int>(count)
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && result.size < count) {
            result.add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return if (result.size == count) result else (0 until count).toList()
    }

    private fun savedQueueFromPlayer(p: Player): List<TrecTrackEnhanced> {
        val currentIndex = p.currentMediaItemIndex
        val currentDuration = p.duration.takeIf { it > 0L } ?: duration
        val tracksSnapshot = (playlistSnapshot() + archivedTracksSnapshot())
            .distinctBy { it.uri.toString() }
        return orderedMediaItemIndices(p).mapNotNull { index ->
            runCatching {
                val item = p.getMediaItemAt(index)
                tracksSnapshot.find { it.uri.toString() == item.mediaId }
                    ?: mediaItemToTrack(
                        mediaItem = item,
                        fallbackDurationMs = if (index == currentIndex) currentDuration else 0L
                    )
            }.getOrNull()
        }.distinctBy { it.uri.toString() }
    }

    private fun fallbackTrackForUri(uriString: String): TrecTrackEnhanced {
        val uri = Uri.parse(uriString)
        val title = uri.lastPathSegment
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: currentTrackTitle.takeIf { it.isNotBlank() && it != "TREC MUSIC" }
            ?: "TREC MUSIC"
        return TrecTrackEnhanced(uri = uri, title = title)
    }

    private fun syncNowPlayingFromPlayer() {
        val p = player ?: return
        val mediaItem = p.currentMediaItem ?: return
        val uriString = mediaItem.mediaId.takeIf { it.isNotBlank() }
            ?: mediaItem.localConfiguration?.uri?.toString()
            ?: return
        val savedState = repository.getPlaybackState()
        val track = playlistSnapshot().find { it.uri.toString() == uriString }
            ?: archivedTracksSnapshot().find { it.uri.toString() == uriString }
            ?: savedState?.queue?.find { it.uri.toString() == uriString }
            ?: mediaItemToTrack(mediaItem, p.duration.takeIf { it > 0L } ?: 0L)

        repeatMode = p.repeatMode
        shuffleMode = savedState?.shuffleMode ?: p.shuffleModeEnabled
        isPlaying = p.isPlaying
        updateNowPlayingFromTrack(track, p.currentPosition.coerceAtLeast(0L))
    }

    private fun getPlaybackTracksForPlaylist(playlistName: String): List<TrecTrackEnhanced> {
        val source = if (playlistName == "All Tracks") {
            playlistSnapshot()
        } else {
            getPlaylistTracks(playlistName)
        }
        return source.distinctBy { it.uri.toString() }
    }

    private fun resetTransientPlaybackState() {
        isReversing = false
        isGeneratingReverse = false
        instrumentalTrackPath = null
        backgroundGenJob?.cancel()
        crossfadeFadeInStartElapsed = -1L
        player?.volume = sleepVolumeFactor.coerceIn(0f, 1f)
        applyPreset("Normal")
    }

    private fun updateNowPlayingFromTrack(track: TrecTrackEnhanced, positionMs: Long = 0L) {
        markCurrentTrackAsPlaybackTarget(track.uri)
        appliedArtworkSignature = null
        currentTrackTitle = track.title
        currentCoverUrl = null
        hasEmbeddedArtwork = false
        currentTrackArtist = getTrackArtist(track)
        currentTrackAlbum = track.album
        currentPosition = positionMs.coerceAtLeast(0L)
        duration = track.durationMs.coerceAtLeast(0L)
        refreshCoverArt(currentTrackArtist, currentTrackTitle, currentTrackAlbum)
    }

    fun playTrackFromPlaylist(playlistName: String, index: Int) {
        runWithNotificationPermission {
            try {
                PlaybackCoordinator.pauseRecorder()
                val tracksToPlay = getPlaybackTracksForPlaylist(playlistName)
                if (tracksToPlay.isEmpty()) return@runWithNotificationPermission

                val safeIndex = index.coerceIn(0, tracksToPlay.lastIndex)
                currentPlaylistFilter = if (playlistName == "All Tracks") null else playlistName
                favoritesContext = null
                archiveContext = null
                resetTransientPlaybackState()

                val mediaItems = tracksToPlay.map { buildSafeMediaItem(it) }

                val p = player ?: return@runWithNotificationPermission
                p.shuffleModeEnabled = shuffleMode
                p.repeatMode = repeatMode
                p.setMediaItems(mediaItems, safeIndex, 0L)
                p.prepare()
                p.play()

                val track = tracksToPlay[safeIndex]
                updateNowPlayingFromTrack(track)
                saveState()
            } catch (t: Throwable) {
                t.printStackTrace()
                Toast.makeText(getApplication(), "Не удалось начать воспроизведение", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun playTrackAtIndex(index: Int) = playTrackFromPlaylist("All Tracks", index)

    fun playTrackFromPlaylistByUri(playlistName: String, uri: Uri) {
        val tracksToPlay = getPlaybackTracksForPlaylist(playlistName)
        val index = tracksToPlay.indexOfFirst { it.uri == uri }
        if (index != -1) {
            playTrackFromPlaylist(playlistName, index)
        }
    }

    fun playShuffledFromPlaylist(playlistName: String) {
        runWithNotificationPermission {
            try {
                PlaybackCoordinator.pauseRecorder()
                val tracksToPlay = getPlaybackTracksForPlaylist(playlistName)
                if (tracksToPlay.isEmpty()) return@runWithNotificationPermission

                currentPlaylistFilter = if (playlistName == "All Tracks") null else playlistName
                favoritesContext = null
                archiveContext = null
                resetTransientPlaybackState()

                val startIndex = if (tracksToPlay.size > 1) Random.nextInt(tracksToPlay.size) else 0
                val mediaItems = tracksToPlay.map { buildSafeMediaItem(it) }

                val p = player ?: return@runWithNotificationPermission
                shuffleMode = true
                p.shuffleModeEnabled = true
                p.repeatMode = repeatMode
                p.setMediaItems(mediaItems, startIndex, 0L)
                p.prepare()
                p.play()

                val track = tracksToPlay[startIndex]
                updateNowPlayingFromTrack(track)
                saveState()
            } catch (t: Throwable) {
                t.printStackTrace()
                Toast.makeText(getApplication(), "Не удалось начать воспроизведение", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun playFromFavorites(favTracks: List<TrecTrackEnhanced>, startIndex: Int, shuffle: Boolean = false) {
        runWithNotificationPermission {
            try {
                PlaybackCoordinator.pauseRecorder()
                if (favTracks.isEmpty()) return@runWithNotificationPermission

                val tracksToPlay = favTracks.distinctBy { it.uri.toString() }
                if (tracksToPlay.isEmpty()) return@runWithNotificationPermission
                val safeIndex = if (shuffle && tracksToPlay.size > 1) {
                    Random.nextInt(tracksToPlay.size)
                } else {
                    startIndex.coerceIn(0, tracksToPlay.lastIndex)
                }

                // Сохраняем контекст — skip next/prev будет ходить по избранному
                favoritesContext = tracksToPlay
                archiveContext = null
                currentPlaylistFilter = null
                resetTransientPlaybackState()

                val mediaItems = tracksToPlay.map { buildSafeMediaItem(it) }

                val p = player ?: return@runWithNotificationPermission
                shuffleMode = shuffle
                p.shuffleModeEnabled = shuffle
                p.repeatMode = repeatMode
                p.setMediaItems(mediaItems, safeIndex, 0L)
                if (shuffle) {
                    p.shuffleModeEnabled = true
                }
                p.prepare()
                p.play()

                val track = tracksToPlay[safeIndex]
                updateNowPlayingFromTrack(track)
                saveState()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    fun playFromArchive(track: TrecTrackEnhanced) {
        runWithNotificationPermission {
            try {
                PlaybackCoordinator.pauseRecorder()
                val tracksToPlay = archivedTracksSnapshot()
                    .ifEmpty { listOf(track) }
                    .distinctBy { it.uri.toString() }
                if (tracksToPlay.isEmpty()) return@runWithNotificationPermission

                val safeIndex = tracksToPlay.indexOfFirst { it.uri == track.uri }
                    .takeIf { it >= 0 }
                    ?: 0

                archiveContext = tracksToPlay
                favoritesContext = null
                currentPlaylistFilter = null
                shuffleMode = false
                resetTransientPlaybackState()

                val mediaItems = tracksToPlay.map { buildSafeMediaItem(it) }
                val p = player ?: return@runWithNotificationPermission
                p.shuffleModeEnabled = false
                p.repeatMode = repeatMode
                p.setMediaItems(mediaItems, safeIndex, 0L)
                p.prepare()
                p.play()

                updateNowPlayingFromTrack(tracksToPlay[safeIndex])
                saveState()
            } catch (t: Throwable) {
                t.printStackTrace()
                Toast.makeText(getApplication(), "Не удалось запустить архив", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun togglePlay() {
        if (isPlaying) {
            player?.pause()
            saveState()
        } else {
            runWithNotificationPermission {
                PlaybackCoordinator.pauseRecorder()
                player?.play()
            }
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
        runWithNotificationPermission {
            val p = player ?: return@runWithNotificationPermission
            if (p.mediaItemCount == 0) return@runWithNotificationPermission
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
            } else if (repeatMode == Player.REPEAT_MODE_ALL) {
                p.seekTo(0, 0L)
            } else {
                return@runWithNotificationPermission
            }
            p.play()
        }
    }

    fun skipPrev() {
        if (isErrorState) { errorAnimationJob?.cancel(); isErrorState = false }
        lastSkipDirection = -1

        // Если трек играет дольше 3 секунд, просто перематываем в начало
        if ((player?.currentPosition ?: 0L) > 3000L) {
            player?.seekTo(0)
            runWithNotificationPermission { player?.play() }
        } else {
            runWithNotificationPermission {
                val p = player ?: return@runWithNotificationPermission
                if (p.mediaItemCount == 0) return@runWithNotificationPermission
                if (p.hasPreviousMediaItem()) {
                    p.seekToPreviousMediaItem()
                } else if (repeatMode == Player.REPEAT_MODE_ALL) {
                    p.seekTo((p.mediaItemCount - 1).coerceAtLeast(0), 0L)
                } else {
                    return@runWithNotificationPermission
                }
                p.play()
            }
        }
    }

    private fun handleCustomTrackSkip(direction: Int) {
        // Приоритет: избранное > плейлист > вся библиотека
        val tracks = archiveContext
            ?: favoritesContext
            ?: if (currentPlaylistFilter != null) libraryHandler.getPlaylistTracks(currentPlaylistFilter!!)
            else playlistSnapshot()
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
        if ((player?.mediaItemCount ?: 0) == 0 || currentTrackUri == null) {
            playShuffledFromPlaylist(currentPlaylistFilter ?: "All Tracks")
            return
        }
        if (!shuffleMode) toggleShuffle()
        skipNext()
    }

    fun seekTo(pos: Long) {
        if (isReversing && duration > 0) player?.seekTo(duration - pos) else player?.seekTo(pos)
        currentPosition = pos
    }

    fun toggleShuffle() {
        shuffleMode = !shuffleMode
        player?.shuffleModeEnabled = shuffleMode
        saveState()
    }

    fun cycleRepeatMode() {
        repeatMode = if (repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else if (repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player?.repeatMode = repeatMode
        saveState()
    }

    fun playExternalFile(context: Context, file: File) {
        runWithNotificationPermission {
            PlaybackCoordinator.pauseRecorder()
            favoritesContext = null
            archiveContext = null
            currentPlaylistFilter = null
            isReversing = false; instrumentalTrackPath = null
            val uri = Uri.fromFile(file)
            currentTrackTitle = file.name
            markCurrentTrackAsPlaybackTarget(uri)
            currentCoverUrl = null
            hasEmbeddedArtwork = false
            currentTrackArtist = null
            currentTrackAlbum = null

            viewModelScope.launch(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val titleRaw = TrackMetadataText.normalizeValue(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    )
                    val artistRaw = TrackMetadataText.normalizeValue(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    )
                    val album = TrackMetadataText.normalizeValue(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    )
                    val titleInfo = TrackMetadataText.inferArtistAndTitle(titleRaw ?: currentTrackTitle)
                    val title = titleInfo.second
                    val artist = artistRaw ?: titleInfo.first
                    withContext(Dispatchers.Main) {
                        if (title.isNotBlank()) currentTrackTitle = title
                        currentTrackArtist = artist
                        currentTrackAlbum = album

                        // ФИКС КРАША: Запрос обложки обращается к ExoPlayer.
                        // Это СТРОГО нужно делать в Main Thread!
                        refreshCoverArt(artist, currentTrackTitle, album)
                    }
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

            val p = player ?: return@runWithNotificationPermission
            p.setMediaItem(item)
            p.prepare()
            p.play()
        }
    }

    fun saveState() {
        val uri = currentTrackUri?.toString()
            ?: player?.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
            ?: return
        val p = player
        val livePosition = p?.currentPosition?.takeIf { it >= 0L } ?: currentPosition
        val queue = if (p != null && p.mediaItemCount > 0) {
            savedQueueFromPlayer(p)
        } else {
            repository.getPlaybackState()?.queue?.takeIf { it.isNotEmpty() }
                ?: playlistSnapshot().takeIf { it.isNotEmpty() }
                ?: listOf(fallbackTrackForUri(uri))
        }
        val currentIndex = queue.indexOfFirst { it.uri.toString() == uri }
            .takeIf { it >= 0 }
            ?: (p?.currentMediaItemIndex ?: 0).coerceAtLeast(0)
        val source = when {
            archiveContext != null -> "archive"
            favoritesContext != null -> "favorites"
            currentPlaylistFilter != null -> "playlist"
            else -> "all"
        }

        repository.savePlaybackState(
            SavedPlaybackState(
                currentUri = uri,
                positionMs = livePosition,
                queue = queue,
                currentIndex = currentIndex,
                shuffleMode = shuffleMode || (p?.shuffleModeEnabled == true),
                repeatMode = repeatMode,
                playlistName = currentPlaylistFilter,
                source = source,
                savedAtMs = System.currentTimeMillis()
            )
        )
    }

    // --- LYRICS METHODS ---
    fun loadLyrics() {
        lyricsJob?.cancel()  // ← отменяем предыдущий запрос
        isLoadingLyrics = true
        lyricsError = null
        currentLyrics = null

        lyricsJob = viewModelScope.launch {
            val uriString = currentTrackUri?.toString()
            val tracksSnapshot = playlistSnapshot()
            val track = tracksSnapshot.find { it.uri.toString() == uriString }
                ?: tracksSnapshot.find { it.title == currentTrackTitle }

            val artist = getTrackArtist(track)
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
        lyricsJob?.cancel()
        currentLyrics = null
        lyricsError = null
        isLoadingLyrics = false
    }

    fun refreshCoverArt(artist: String?, title: String?, album: String?) {
        // Гарантируем Main Thread независимо от того, кто вызывает
        viewModelScope.launch(Dispatchers.Main) {
            ensureCoverArt(
                artist = artist,
                title = title,
                album = album,
                activeUri = currentTrackUri?.toString()
            )
            ensureCurrentTrackMetadata()
        }
    }

    fun getCoverUrlForTrack(track: TrecTrackEnhanced): String? {
        val key = coverCacheKey(track.artist, track.title, track.album)
        return coverUrlCache[key]
    }

    fun ensureCoverForTrack(track: TrecTrackEnhanced) {
        ensureCoverArt(
            artist = track.artist,
            title = track.title,
            album = track.album,
            activeUri = null
        )
        ensureMetadataForTrack(track)
    }

    private fun metadataCacheKey(artist: String?, title: String?, album: String?): String {
        return coverCacheKey(artist, title, album)
    }

    private fun normalizeRemoteMetadata(meta: RemoteTrackMetadata): RemoteTrackMetadata? {
        val title = TrackMetadataText.normalizeValue(meta.title)
        val artist = TrackMetadataText.normalizeValue(meta.artist)
        val album = TrackMetadataText.normalizeValue(meta.album)
        val genre = TrackMetadataText.normalizeValue(meta.genre)
        val year = meta.year?.takeIf { it in 1900..2100 }
        if (title == null && artist == null && album == null && genre == null && year == null) return null
        return RemoteTrackMetadata(
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            year = year
        )
    }

    private fun remoteMetadataToJson(meta: RemoteTrackMetadata): String {
        val obj = JSONObject()
        meta.title?.let { obj.put("title", it) }
        meta.artist?.let { obj.put("artist", it) }
        meta.album?.let { obj.put("album", it) }
        meta.genre?.let { obj.put("genre", it) }
        meta.year?.let { obj.put("year", it) }
        return obj.toString()
    }

    private fun remoteMetadataFromJson(raw: String?): RemoteTrackMetadata? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            RemoteTrackMetadata(
                title = obj.optString("title", "").ifBlank { null },
                artist = obj.optString("artist", "").ifBlank { null },
                album = obj.optString("album", "").ifBlank { null },
                genre = obj.optString("genre", "").ifBlank { null },
                year = if (obj.has("year")) obj.optInt("year").takeIf { it > 0 } else null
            )
        }.getOrNull()?.let { normalizeRemoteMetadata(it) }
    }

    private fun scheduleTrackCacheSave() {
        metadataPersistJob?.cancel()
        metadataPersistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1200L)
            val tracksSnapshot = withContext(Dispatchers.Main) { playlistSnapshot() }
            repository.saveTrackCache(tracksSnapshot)
        }
    }

    private fun mergeTrackWithRemoteMetadata(
        track: TrecTrackEnhanced,
        remote: RemoteTrackMetadata
    ): TrecTrackEnhanced {
        val currentArtist = TrackMetadataText.normalizeValue(track.artist)
        val currentAlbum = TrackMetadataText.normalizeValue(track.album)
        val currentGenre = TrackMetadataText.normalizeValue(track.genre)

        val inferred = TrackMetadataText.inferArtistAndTitle(track.title)
        val titleLooksGenerated = track.title.equals("Unknown Track", ignoreCase = true) ||
            (currentArtist == null && inferred.first != null)

        val mergedTitle = when {
            remote.title != null && titleLooksGenerated -> remote.title
            else -> track.title
        }
        val mergedArtist = currentArtist ?: remote.artist
        val mergedAlbum = currentAlbum ?: remote.album
        val mergedGenre = currentGenre ?: remote.genre
        val mergedYear = track.year ?: remote.year

        return track.copy(
            title = mergedTitle,
            artist = mergedArtist,
            album = mergedAlbum,
            genre = mergedGenre,
            year = mergedYear
        )
    }

    private fun applyRemoteMetadataToTrack(trackUri: String, remote: RemoteTrackMetadata, activeUri: String?) {
        val idx = playlist.indexOfFirst { it.uri.toString() == trackUri }
        if (idx == -1) return

        val oldTrack = playlist[idx]
        val updatedTrack = mergeTrackWithRemoteMetadata(oldTrack, remote)
        val trackChanged = updatedTrack != oldTrack
        if (trackChanged) {
            playlist[idx] = updatedTrack
            playlistUpdateTrigger++
            scheduleTrackCacheSave()
        }

        val active = activeUri ?: currentTrackUri?.toString()
        if (active == trackUri) {
            val prevTitle = currentTrackTitle
            val prevArtist = currentTrackArtist
            val prevAlbum = currentTrackAlbum
            currentTrackTitle = updatedTrack.title
            currentTrackArtist = updatedTrack.artist
            currentTrackAlbum = updatedTrack.album
            if (trackChanged || prevTitle != currentTrackTitle || prevArtist != currentTrackArtist || prevAlbum != currentTrackAlbum) {
                refreshCoverArt(updatedTrack.artist, updatedTrack.title, updatedTrack.album)
            }
            if (trackChanged) {
                tryUpdateMediaStoreMetadata(updatedTrack.uri, updatedTrack)
            }
        }
    }

    private fun ensureTrackMetadataForTrack(
        trackUri: String,
        artist: String?,
        title: String?,
        album: String?,
        activeUri: String?
    ) {
        val safeTitle = TrackMetadataText.normalizeValue(title) ?: return
        val safeArtist = TrackMetadataText.normalizeValue(artist)
        val safeAlbum = TrackMetadataText.normalizeValue(album)
        val key = metadataCacheKey(safeArtist, safeTitle, safeAlbum)

        metadataCache[key]?.let { cached ->
            applyRemoteMetadataToTrack(trackUri, cached, activeUri)
            return
        }

        remoteMetadataFromJson(prefs.getCachedTrackMetadata(key))?.let { cached ->
            metadataCache[key] = cached
            applyRemoteMetadataToTrack(trackUri, cached, activeUri)
            return
        }

        val existingJob = metadataFetchJobs[key]
        if (existingJob?.isActive == true) {
            viewModelScope.launch(Dispatchers.Main) {
                runCatching { existingJob.join() }
                metadataCache[key]?.let { applyRemoteMetadataToTrack(trackUri, it, activeUri) }
            }
            return
        }

        val isHighPriority = activeUri != null && currentTrackUri?.toString() == activeUri
        val job = viewModelScope.launch(Dispatchers.IO) {
            if (!isHighPriority) {
                delay(120L)
                if (metadataFetchJobs.size >= 14) return@launch
            }

            metadataFetchLimiter.withPermit {
                val fetched = trackMetadataService.fetchMetadata(safeArtist, safeTitle, safeAlbum)
                val normalized = fetched?.let { normalizeRemoteMetadata(it) } ?: return@withPermit

                metadataCache[key] = normalized
                prefs.saveCachedTrackMetadata(key, remoteMetadataToJson(normalized))

                withContext(Dispatchers.Main) {
                    applyRemoteMetadataToTrack(trackUri, normalized, activeUri)
                }
            }
        }

        metadataFetchJobs[key] = job
        job.invokeOnCompletion { metadataFetchJobs.remove(key) }
    }

    private fun tryUpdateMediaStoreMetadata(uri: Uri, track: TrecTrackEnhanced) {
        if (uri.scheme != "content") return
        val values = android.content.ContentValues().apply {
            put(MediaStore.Audio.Media.TITLE, track.title)
            TrackMetadataText.normalizeValue(track.artist)?.let { put(MediaStore.Audio.Media.ARTIST, it) }
            TrackMetadataText.normalizeValue(track.album)?.let { put(MediaStore.Audio.Media.ALBUM, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                TrackMetadataText.normalizeValue(track.genre)?.let { put(MediaStore.Audio.Media.GENRE, it) }
            }
            track.year?.takeIf { it > 0 }?.let { put(MediaStore.Audio.Media.YEAR, it) }
        }
        if (values.size() == 0) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.update(uri, values, null, null)
            }
        }
    }

    @kotlin.OptIn(ExperimentalCoilApi::class)
    fun clearCoverCache(context: Context) {
        prefs.clearCoverCache()
        coverUrlCache.clear()
        coverColorCache.clear()
        if (!hasEmbeddedArtwork) currentCoverUrl = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loader = context.imageLoader
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            } catch (_: Exception) {
            }
        }
    }

    fun refreshAppCacheSize(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = try { computeAppCacheBytes(context) } catch (_: Exception) { 0L }
            val formatted = formatBytes(bytes)
            withContext(Dispatchers.Main) { appCacheSize = formatted }
        }
    }

    fun clearAppCache(context: Context) {
        // Обложки (Coil + prefs)
        clearCoverCache(context)
        prefs.clearTrackMetadataCache()
        metadataCache.clear()

        // DSP/обработка (rev_/inst_ + сброс состояний)
        clearReverseCache(context)

        // На всякий случай убираем "временные" raw-файлы, которые могли остаться после обработки
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.cacheDir
                    .listFiles { _, name ->
                        (name.startsWith("temp_decode_") && name.endsWith(".raw")) ||
                            (name.startsWith("vr_") && name.endsWith(".raw"))
                    }
                    ?.forEach { it.delete() }
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                refreshAppCacheSize(context)
            }
        }
    }

    private fun computeAppCacheBytes(context: Context): Long {
        val cacheDir = context.cacheDir

        // 1) DSP / обработка
        val processingBytes = try {
            cacheDir.listFiles { _, name ->
                ((name.startsWith("rev_") || name.startsWith("inst_") || name.startsWith("sep_umxl1_")) && name.endsWith(".wav")) ||
                    (name.startsWith("temp_decode_") && name.endsWith(".raw")) ||
                    (name.startsWith("vr_") && name.endsWith(".raw"))
            }?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) {
            0L
        }

        // 2) Обложки (Coil disk cache)
        val coverDiskBytes = try {
            dirBytes(cacheDir.resolve("image_cache"))
        } catch (_: Exception) {
            0L
        }

        return processingBytes + coverDiskBytes
    }

    private fun dirBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        for (c in children) {
            total += if (c.isDirectory) dirBytes(c) else c.length()
        }
        return total
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun setEmbeddedArtworkForCurrentTrack(bitmap: Bitmap, activeUri: String) {
        // updateCurrentMediaItemArtwork убрана (была причиной StackOverflow).
        // Встроенную обложку Media3 загружает из файла самостоятельно.
        // Функция оставлена чтобы не менять вызовы в MetadataHandler.
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap, maxSizePx: Int = 512, quality: Int = 86): ByteArray? {
        return try {
            val w = bitmap.width.coerceAtLeast(1)
            val h = bitmap.height.coerceAtLeast(1)
            val scale = min(maxSizePx.toFloat() / w.toFloat(), maxSizePx.toFloat() / h.toFloat()).coerceAtMost(1f)
            val scaled = if (scale < 1f) {
                val nw = (w * scale).roundToInt().coerceAtLeast(1)
                val nh = (h * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, nw, nh, true)
            } else {
                bitmap
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), out)
            out.toByteArray().takeIf { it.isNotEmpty() && it.size <= 800_000 }
        } catch (_: OutOfMemoryError) {
            // ФИКС OOM: Защита от падения при жесткой нехватке оперативки (например на старых телефонах)
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun coverCacheKey(artist: String?, title: String?, album: String?): String {
        fun n(v: String?): String {
            return v
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.lowercase(Locale.ROOT)
                .orEmpty()
        }
        return listOf(n(artist), n(title), n(album)).joinToString("|")
    }

    private fun ensureCoverArt(
        artist: String?,
        title: String?,
        album: String?,
        activeUri: String?
    ) {
        val isCurrentRequest = activeUri != null && currentTrackUri?.toString() == activeUri
        if (isCurrentRequest && hasEmbeddedArtwork) return

        val safeTitle = title?.trim().orEmpty()
        if (safeTitle.isBlank()) return

        val key = coverCacheKey(artist, safeTitle, album)

        // 1) In-memory state cache (fast path)
        coverUrlCache[key]?.let { url ->
            if (activeUri != null && currentTrackUri?.toString() == activeUri && !hasEmbeddedArtwork) {
                currentCoverUrl = url
                maybeApplyCoverPaletteFromCache(key, activeUri)
                updateCurrentMediaItemArtwork(url = url, artworkData = null, activeUri = activeUri)
                ensureNotificationArtworkPrepared(key, url, activeUri)
            }
            return
        }

        // 2) Persistent cache
        val persistedUrl = prefs.getCachedCoverUrl(key)
        if (!persistedUrl.isNullOrBlank()) {
            coverUrlCache[key] = persistedUrl
            if (activeUri != null && currentTrackUri?.toString() == activeUri && !hasEmbeddedArtwork) {
                currentCoverUrl = persistedUrl
                maybeApplyCoverPaletteFromCache(key, activeUri)
                updateCurrentMediaItemArtwork(url = persistedUrl, artworkData = null, activeUri = activeUri)
                ensureNotificationArtworkPrepared(key, persistedUrl, activeUri)
            }
            return
        } else {
            // Even if URL isn't cached yet, color might be.
            maybeApplyCoverPaletteFromCache(key, activeUri)
        }

        // 3) Fetch (deduped + limited)
        if (coverFetchJobs[key]?.isActive == true) return

        // Для текущего трека — максимальный приоритет.
        // Для остальных треков (обложки в списке) — ограничиваем общий шум.
        val isHighPriority = isCurrentRequest

        val job = viewModelScope.launch(Dispatchers.IO) {
            if (!isHighPriority) {
                // Небольшой debounce, чтобы быстрый скролл не создавал "шторм" запросов.
                delay(80L)
                if (coverFetchJobs.size >= 12) return@launch
            }

            coverFetchLimiter.withPermit {
                val url = coverArtService.fetchCoverUrl(artist, safeTitle, album)
                if (!url.isNullOrBlank()) {
                    prefs.saveCachedCoverUrl(key, url)
                    withContext(Dispatchers.Main) {
                        coverUrlCache[key] = url
                        if (activeUri != null && currentTrackUri?.toString() == activeUri && !hasEmbeddedArtwork) {
                            currentCoverUrl = url
                            updateCurrentMediaItemArtwork(url = url, artworkData = null, activeUri = activeUri)
                        }
                    }

                    // Prefetch and extract palette off the main thread.
                    // Генерируем Palette ТОЛЬКО если это текущий играющий трек.
                    // Для списков (LazyColumn) цвета нам не нужны, не будем грузить процессор!
                    if (isHighPriority) {
                        val app = getApplication<Application>()
                        try {
                            val req = ImageRequest.Builder(app)
                                .data(url)
                                .allowHardware(false)
                                .size(512)
                                .build()
                            val result = app.imageLoader.execute(req)
                            val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                            if (bmp != null) {
                                val palette = try { Palette.from(bmp).generate() } catch (e: Exception) { null }
                                val dom = palette?.chooseTrecAccentColor() ?: 0xFFD50000.toInt()
                                val sec = palette?.getDarkMutedColor(0xFF050505.toInt()) ?: 0xFF050505.toInt()
                                prefs.saveCachedCoverColorArgb(key, dom)

                                withContext(Dispatchers.Main) {
                                    coverColorCache[key] = Color(dom)
                                    if (isDynamicColorEnabled && !hasEmbeddedArtwork) {
                                        dominantColor = Color(dom)
                                        secondaryColor = Color(sec)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        coverFetchJobs[key] = job
        job.invokeOnCompletion { coverFetchJobs.remove(key) }
    }

    private fun maybeApplyCoverPaletteFromCache(key: String, activeUri: String?) {
        if (!isDynamicColorEnabled) return
        if (hasEmbeddedArtwork) return
        if (currentTrackUri?.toString() != activeUri) return

        val cachedColor = prefs.getCachedCoverColorArgb(key) ?: return
        coverColorCache[key] = Color(cachedColor)
        dominantColor = Color(cachedColor)
        // secondaryColor is best-effort; keep current if we don't have it.
    }

    private fun ensureNotificationArtworkPrepared(key: String, url: String, activeUri: String?) {
        if (activeUri == null) return
        if (currentTrackUri?.toString() != activeUri) return
        if (hasEmbeddedArtwork) return
        if (coverFetchJobs[key]?.isActive == true) return

        val job = viewModelScope.launch(Dispatchers.IO) {
            coverFetchLimiter.withPermit {
                val app = getApplication<Application>()
                try {
                    val req = ImageRequest.Builder(app)
                        .data(url)
                        .allowHardware(false)
                        .size(512)
                        .build()
                    val result = app.imageLoader.execute(req)
                    val bmp = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withPermit
                    val palette = try { Palette.from(bmp).generate() } catch (_: Exception) { null }
                    val dom = palette?.chooseTrecAccentColor()
                    val sec = palette?.getDarkMutedColor(0xFF050505.toInt())
                    if (dom != null) {
                        prefs.saveCachedCoverColorArgb(key, dom)
                    }
                    withContext(Dispatchers.Main) {
                        if (!hasEmbeddedArtwork && currentTrackUri?.toString() == activeUri) {
                            if (dom != null) {
                                coverColorCache[key] = Color(dom)
                                if (isDynamicColorEnabled) {
                                    dominantColor = Color(dom)
                                    if (sec != null) secondaryColor = Color(sec)
                                }
                            }
                            // Передаем только URL! Никаких байтов.
                            updateCurrentMediaItemArtwork(url = url, activeUri = activeUri)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        coverFetchJobs[key] = job
        job.invokeOnCompletion { coverFetchJobs.remove(key) }
    }

    private fun updateCurrentMediaItemArtwork(url: String?, artworkData: ByteArray? = null, activeUri: String?) {
        if (activeUri == null || currentTrackUri?.toString() != activeUri) return
        if (url.isNullOrBlank() && artworkData == null) return

        val signature = "$activeUri|${url.orEmpty()}|${artworkData?.size ?: 0}"
        if (signature == appliedArtworkSignature) return
        appliedArtworkSignature = signature
    }

    fun restoreLastTrack() {
        val p = player ?: return
        if (p.currentMediaItem != null) {
            syncNowPlayingFromPlayer()
            return
        }

        val saved = repository.getPlaybackState()
        val lastUri = saved?.currentUri ?: repository.getLastTrackUri() ?: return
        val lastPosition = saved?.positionMs ?: repository.getLastTrackPos().coerceAtLeast(0L)
        val cachedByUri = (playlistSnapshot() + archivedTracksSnapshot())
            .distinctBy { it.uri.toString() }
            .associateBy { it.uri.toString() }
        val savedQueue = saved?.queue.orEmpty()
        val restoredTracks = when {
            savedQueue.isNotEmpty() -> savedQueue.map { cachedByUri[it.uri.toString()] ?: it }
            cachedByUri.isNotEmpty() -> cachedByUri.values.toList()
            else -> listOf(fallbackTrackForUri(lastUri))
        }.distinctBy { it.uri.toString() }

        if (restoredTracks.isEmpty()) return
        val restoredIndex = restoredTracks.indexOfFirst { it.uri.toString() == lastUri }
            .takeIf { it >= 0 }
            ?: saved?.currentIndex?.coerceIn(0, restoredTracks.lastIndex)
            ?: 0

        repeatMode = saved?.repeatMode ?: repeatMode
        val restoredShuffleMode = saved?.shuffleMode ?: shuffleMode
        currentPlaylistFilter = saved?.playlistName
        favoritesContext = if (saved?.source == "favorites") restoredTracks else null
        archiveContext = if (saved?.source == "archive") restoredTracks else null

        p.shuffleModeEnabled = false
        shuffleMode = restoredShuffleMode
        p.repeatMode = repeatMode
        p.setMediaItems(restoredTracks.map { buildSafeMediaItem(it) }, restoredIndex, lastPosition.coerceAtLeast(0L))
        p.prepare()

        updateNowPlayingFromTrack(restoredTracks[restoredIndex], lastPosition)
        saveState()
    }
    fun getAllTracks(): List<TrecTrackEnhanced> = playlistSnapshot()

    fun getFilteredPlaylist(): List<TrecTrackEnhanced> {
        val tracks = playlistSnapshot()
        if (currentPlaylistFilter == null) return tracks
        val allowedUris = repository.getTracksInPlaylist(currentPlaylistFilter!!)
        return tracks.filter { allowedUris.contains(it.uri.toString()) }
    }

    fun removeTrackFromActiveQueue(uriString: String) {
        val p = player ?: return
        val indices = (0 until p.mediaItemCount).filter { index ->
            runCatching {
                val item = p.getMediaItemAt(index)
                item.mediaId == uriString || item.localConfiguration?.uri?.toString() == uriString
            }.getOrDefault(false)
        }
        if (indices.isEmpty()) return

        val wasCurrent = indices.contains(p.currentMediaItemIndex)
        val wasPlaying = p.isPlaying
        if (indices.size >= p.mediaItemCount) {
            stopAndClear()
            return
        }

        indices.asReversed().forEach { index ->
            runCatching { p.removeMediaItem(index) }
        }

        if (wasCurrent) {
            syncNowPlayingFromPlayer()
            if (wasPlaying) {
                runWithNotificationPermission { p.play() }
            }
        }
        saveState()
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
        isVocalRemovalProcessing = false
        vocalRemovalProcessingUri = null
        favoritesContext = null
        archiveContext = null
        currentPlaylistFilter = null
        repository.clearPlaybackState()
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
                if (playing && !isErrorState) {
                    startProgressUpdater()
                } else {
                    audioAnalysis = AudioAnalysisFrame.Silent
                }
            }
            override fun onMediaItemTransition(mi: MediaItem?, r: Int) {
                try {
                    sensorHandler.stopScratchLoop(); isErrorState = false
                    if (isScratching) return

                    if (mi?.mediaId != reverseTrackPath && mi?.mediaId != instrumentalTrackPath) {
                        if (isReversing) isReversing = false
                        normalTrackUri = null
                        metadataHandler.updateCurrentTrackInfo(getApplication(), mi)
                        saveState()
                    }

                    crossfadeFadeInStartElapsed = -1L
                    player?.volume = sleepVolumeFactor.coerceIn(0f, 1f)
                } catch (t: Throwable) {
                    // Listener не должен ронять процесс.
                    t.printStackTrace()
                }
            }
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY) {
                    duration = p.duration.coerceAtLeast(0)
                    p.volume = sleepVolumeFactor.coerceIn(0f, 1f)
                    crossfadeFadeInStartElapsed = -1L
                }
                if (s == Player.STATE_ENDED && !isErrorState) lastSkipDirection = 1
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                shuffleMode = shuffleModeEnabled
            }
            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
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
                val real = player?.currentPosition ?: 0L
                if (!isScratching) {
                    if (!isErrorState) currentPosition = if (isReversing) (duration - real).coerceAtLeast(0) else real

                    if (isVinylModeEnabled) {
                        if (isErrorState) vinylRotationAngle += 30f
                        else if (player?.isLoading == false) {
                            val dir = if (isReversing) -1.0f else 1.0f
                            val effectiveSpeed = playbackSpeed
                                .takeIf { it > 0f }
                                ?: (player?.playbackParameters?.speed ?: 1f)
                            val degreesPerTick = (vinylBaseRpm * 360f / 60f) * (vinylTickIntervalMs / 1000f)
                            vinylRotationAngle += degreesPerTick * dir * effectiveSpeed
                        }
                    }
                }

                // Автоматическое управление громкостью (таймер сна + кроссфейд)
                applyAutomatedVolume(realPositionMs = real)
                audioAnalysis = if (isPlaying && !isErrorState) {
                    AudioAnalysisBus.latest()
                } else {
                    AudioAnalysisFrame.Silent
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

    private fun applyAutomatedVolume(realPositionMs: Long) {
        val p = player ?: return

        // Реальный overlap-кроссфейд здесь не делаем: одинарный fade-out перед следующим
        // треком слышится как секундное "притухание" в начале новой песни.
        crossfadeFadeInStartElapsed = -1L
        val target = sleepVolumeFactor.coerceIn(0f, 1f)
        try {
            if (abs(p.volume - target) > 0.02f) {
                p.volume = target
            }
        } catch (_: Exception) {
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
