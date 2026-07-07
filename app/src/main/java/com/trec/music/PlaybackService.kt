// PlaybackService.kt
//
// ТИП: Android Service (Media3)
//
// ВЕРСИЯ: ULTIMATE RELEASE (HOTFIX)
//
// ИСПРАВЛЕНИЯ:
// 1. buildAudioSink: Исправлена сигнатура метода (удален аргумент enableOffload),
//    чтобы соответствовать используемой версии Media3.

package com.trec.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.trec.music.data.TrecTrackEnhanced
import com.trec.music.utils.TrecAudioProcessor

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var prefs: PrefsManager
    private var listenStartElapsedMs: Long? = null

    // Отдельные процессоры нужны, потому что при кроссфейде два ExoPlayer играют параллельно.
    private val sessionAudioProcessor = TrecAudioProcessor()
    private val crossfadeAudioProcessor = TrecAudioProcessor()
    private var crossfadePlayer: ExoPlayer? = null
    private val crossfadeHandler = Handler(Looper.getMainLooper())
    private var crossfadeWatchRunnable: Runnable? = null
    private var crossfadeFadeRunnable: Runnable? = null
    private var pendingCrossfadeMediaId: String? = null
    private var pendingCrossfadeIndex: Int = C.INDEX_UNSET
    private var crossfadeHandoffInProgress: Boolean = false
    private var crossfadeStartedAtMs: Long = 0L
    private var crossfadeDurationMs: Long = 0L
    private var crossfadeBaseVolume: Float = 1f

    companion object {
        const val CMD_UPDATE_SETTINGS = "TREC_UPDATE_SETTINGS"
        const val CMD_TOGGLE_REPEAT = "TREC_TOGGLE_REPEAT"
        const val CMD_TOGGLE_SHUFFLE = "TREC_TOGGLE_SHUFFLE"
        const val CMD_RESTORE_QUEUE = "TREC_RESTORE_QUEUE"

        private const val CROSSFADE_HANDOFF_LEAD_MIN_MS = 120L
        private const val CROSSFADE_HANDOFF_LEAD_MAX_MS = 320L
        private const val CROSSFADE_HANDOFF_LATENCY_COMP_MS = 60L

        private const val GENTLE_MINIMUM_SILENCE_DURATION_US = 650_000L
        private const val GENTLE_SILENCE_RETENTION_RATIO = 0.38f
        private const val GENTLE_MAX_SILENCE_TO_KEEP_DURATION_US = 1_500_000L
        private const val GENTLE_MIN_VOLUME_TO_KEEP_PERCENTAGE = 12
        private const val GENTLE_SILENCE_THRESHOLD_LEVEL = 512
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        setMediaNotificationProvider(GlassMediaNotificationProvider(this))

        syncAudioProcessorSettings()

        val player = buildPlayer(sessionAudioProcessor, handleAudioFocus = true)
        crossfadePlayer = buildPlayer(crossfadeAudioProcessor, handleAudioFocus = false)

        fun commitListeningTime() {
            val start = listenStartElapsedMs ?: return
            val delta = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
            listenStartElapsedMs = null
            prefs.addListeningTime(delta)
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    if (listenStartElapsedMs == null) {
                        listenStartElapsedMs = SystemClock.elapsedRealtime()
                        prefs.incrementListenSession()
                    }
                    startCrossfadeWatcher(player)
                } else {
                    stopCrossfadeWatcher()
                    if (!player.playWhenReady ||
                        player.playbackState == Player.STATE_IDLE ||
                        player.playbackState == Player.STATE_ENDED
                    ) {
                        cancelCrossfade(restorePrimaryVolume = true)
                    }
                    commitListeningTime()
                    saveLastPlaybackState(player)
                }
                TrecMusicWidgetProvider.updateAll(this@PlaybackService, player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return
                val crossfadeHandedOff = handOffCrossfadeIfNeeded(player, mediaItem)
                if (!crossfadeHandedOff && pendingCrossfadeMediaId != null) {
                    cancelCrossfade(restorePrimaryVolume = true)
                }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
                prefs.incrementTracksStarted()
                prefs.incrementTrackPlayCount(mediaItem.mediaId)
                saveLastPlaybackState(player)
                TrecMusicWidgetProvider.updateAll(this@PlaybackService, player)
                if (player.isPlaying) startCrossfadeWatcher(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    saveLastPlaybackState(player)
                    TrecMusicWidgetProvider.updateAll(this@PlaybackService, player)
                }
                if (playbackState == Player.STATE_READY && player.isPlaying) {
                    startCrossfadeWatcher(player)
                }
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    cancelCrossfade(restorePrimaryVolume = true)
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                saveLastPlaybackState(player, overrideShuffleMode = shuffleModeEnabled)
                TrecMusicWidgetProvider.updateAll(this@PlaybackService, player)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                saveLastPlaybackState(player)
                TrecMusicWidgetProvider.updateAll(this@PlaybackService, player)
            }
        })

        // 5. Intent для уведомления
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 6. Инициализация сессии
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val connectionSession = session.player as? ExoPlayer
                    val sessionExtras = Bundle()

                    // Передаем ID сессии для визуализатора
                    if (connectionSession != null) {
                        sessionExtras.putInt("AUDIO_SESSION_ID", connectionSession.audioSessionId)
                    }

                    // Разрешаем наши кастомные команды
                    val availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_UPDATE_SETTINGS, Bundle.EMPTY))
                        .add(SessionCommand(CMD_TOGGLE_REPEAT, Bundle.EMPTY))
                        .add(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
                        .add(SessionCommand(CMD_RESTORE_QUEUE, Bundle.EMPTY))
                        .build()

                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(availableSessionCommands)
                        .setSessionExtras(sessionExtras)
                        .build()
                }

                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean
                ): ListenableFuture<MediaItemsWithStartPosition> {
                    val resume = buildResumeItems()
                    if (resume.mediaItems.isEmpty()) {
                        return Futures.immediateFailedFuture(UnsupportedOperationException("No saved queue"))
                    }
                    return Futures.immediateFuture(resume)
                }

                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    } ?: return false

                    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true

                    val p = session.player
                    if (p.mediaItemCount == 0) {
                        restoreQueueFromPrefs(p, play = event.keyCode.isPlayLikeCommand())
                    }

                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            if (p.mediaItemCount == 0) return true
                            if (p.playbackState == Player.STATE_IDLE) p.prepare()
                            p.play()
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (p.mediaItemCount == 0) return true
                            if (p.isPlaying) {
                                p.pause()
                            } else {
                                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                                p.play()
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> p.pause()
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                            if (p.mediaItemCount > 0 && p.playbackState == Player.STATE_IDLE) p.prepare()
                            if (p.mediaItemCount > 0) p.play()
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (p.currentPosition > 3000L) {
                                p.seekTo(0L)
                            } else if (p.hasPreviousMediaItem()) {
                                p.seekToPreviousMediaItem()
                            }
                            if (p.mediaItemCount > 0 && p.playbackState == Player.STATE_IDLE) p.prepare()
                            if (p.mediaItemCount > 0) p.play()
                        }
                        KeyEvent.KEYCODE_MEDIA_STOP -> p.stop()
                        else -> return false
                    }

                    saveLastPlaybackState(p)
                    TrecMusicWidgetProvider.updateAll(this@PlaybackService, p)
                    return true
                }

                // Обработка команд от ViewModel (Settings)
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CMD_TOGGLE_REPEAT) {
                        val p = session.player
                        p.repeatMode = when (p.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        saveLastPlaybackState(p)
                        TrecMusicWidgetProvider.updateAll(this@PlaybackService, p)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }

                    if (customCommand.customAction == CMD_TOGGLE_SHUFFLE) {
                        val p = session.player
                        val savedShuffle = prefs.getPlaybackState()?.shuffleMode ?: p.shuffleModeEnabled
                        val nextShuffle = !savedShuffle
                        p.shuffleModeEnabled = nextShuffle
                        saveLastPlaybackState(p, overrideShuffleMode = nextShuffle)
                        TrecMusicWidgetProvider.updateAll(this@PlaybackService, p)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }

                    if (customCommand.customAction == CMD_RESTORE_QUEUE) {
                        val play = args.getBoolean("play", false)
                        val restored = restoreQueueFromPrefs(session.player, play)
                        return Futures.immediateFuture(
                            SessionResult(
                                if (restored) SessionResult.RESULT_SUCCESS
                                else SessionResult.RESULT_ERROR_BAD_VALUE
                            )
                        )
                    }

                    if (customCommand.customAction == CMD_UPDATE_SETTINGS) {
                        val p = session.player as? ExoPlayer

                        syncAudioProcessorSettings()

                        // Обновляем параметры ExoPlayer
                        p?.let {
                            applyPlayerRuntimeSettings(it, handleAudioFocus = true)
                        }
                        applyPlayerRuntimeSettings(crossfadePlayer, handleAudioFocus = false)

                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        saveLastPlaybackState(player)
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        stopCrossfadeWatcher()
        cancelCrossfade(restorePrimaryVolume = false)
        saveLastPlaybackState(mediaSession?.player)
        // If the service is killed while playing, commit the current listening session.
        val start = listenStartElapsedMs
        if (start != null) {
            val delta = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
            listenStartElapsedMs = null
            prefs.addListeningTime(delta)
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        crossfadePlayer?.release()
        crossfadePlayer = null
        super.onDestroy()
    }

    private fun buildAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
    }

    private fun createRenderersFactory(audioProcessor: TrecAudioProcessor): DefaultRenderersFactory {
        return try {
            object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return try {
                        DefaultAudioSink.Builder(context)
                            .setAudioProcessorChain(
                                DefaultAudioSink.DefaultAudioProcessorChain(
                                    arrayOf<AudioProcessor>(audioProcessor),
                                    createGentleSilenceSkippingProcessor(),
                                    SonicAudioProcessor()
                                )
                            )
                            .setEnableFloatOutput(false)
                            .build()
                    } catch (_: Throwable) {
                        null
                    }
                }
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
        } catch (_: Exception) {
            DefaultRenderersFactory(this)
        }
    }

    private fun createGentleSilenceSkippingProcessor(): SilenceSkippingAudioProcessor {
        return SilenceSkippingAudioProcessor(
            GENTLE_MINIMUM_SILENCE_DURATION_US,
            GENTLE_SILENCE_RETENTION_RATIO,
            GENTLE_MAX_SILENCE_TO_KEEP_DURATION_US,
            GENTLE_MIN_VOLUME_TO_KEEP_PERCENTAGE,
            GENTLE_SILENCE_THRESHOLD_LEVEL.toShort()
        )
    }

    private fun buildPlayer(
        audioProcessor: TrecAudioProcessor,
        handleAudioFocus: Boolean
    ): ExoPlayer {
        return ExoPlayer.Builder(this, createRenderersFactory(audioProcessor))
            .setLooper(Looper.getMainLooper())
            .setAudioAttributes(
                buildAudioAttributes(),
                handleAudioFocus && !prefs.getAudioFocusIgnore()
            )
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSkipSilenceEnabled(prefs.getSkipSilence())
            .build()
    }

    private fun syncAudioProcessorSettings() {
        listOf(sessionAudioProcessor, crossfadeAudioProcessor).forEach { processor ->
            processor.balance = prefs.getAudioBalance()
            processor.isMono = prefs.getMonoAudio()
        }
    }

    private fun applyPlayerRuntimeSettings(player: ExoPlayer?, handleAudioFocus: Boolean) {
        player ?: return
        player.skipSilenceEnabled = prefs.getSkipSilence()
        player.setAudioAttributes(
            buildAudioAttributes(),
            handleAudioFocus && !prefs.getAudioFocusIgnore()
        )
    }

    private fun startCrossfadeWatcher(player: ExoPlayer) {
        stopCrossfadeWatcher()

        val watcher = object : Runnable {
            override fun run() {
                val sessionPlayer = mediaSession?.player as? ExoPlayer
                if (sessionPlayer !== player || !player.isPlaying) return

                maybeStartCrossfade(player)
                crossfadeHandler.postDelayed(this, 180L)
            }
        }
        crossfadeWatchRunnable = watcher
        crossfadeHandler.postDelayed(watcher, 180L)
    }

    private fun stopCrossfadeWatcher() {
        crossfadeWatchRunnable?.let { crossfadeHandler.removeCallbacks(it) }
        crossfadeWatchRunnable = null
    }

    private fun maybeStartCrossfade(player: ExoPlayer) {
        if (pendingCrossfadeMediaId != null) return
        if (crossfadeHandoffInProgress) return
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return

        val configuredMs = prefs.getCrossfade().coerceIn(0, 12_000)
        if (configuredMs < 500) return

        val duration = player.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return

        val effectiveMs = configuredMs
            .coerceAtMost((duration - 1_000L).coerceAtLeast(0L).toInt())
            .coerceAtLeast(0)
        if (effectiveMs < 500) return

        val remaining = (duration - player.currentPosition).coerceAtLeast(0L)
        if (remaining > effectiveMs || remaining <= 120L) return

        val nextIndex = nextMediaItemIndex(player)
        if (nextIndex == C.INDEX_UNSET) return

        val nextItem = runCatching { player.getMediaItemAt(nextIndex) }.getOrNull() ?: return
        if (nextItem.mediaId.isBlank()) return

        startCrossfade(player, nextIndex, nextItem, effectiveMs.toLong())
    }

    private fun nextMediaItemIndex(player: ExoPlayer): Int {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return C.INDEX_UNSET
        return timeline.getNextWindowIndex(
            player.currentMediaItemIndex,
            player.repeatMode,
            player.shuffleModeEnabled
        )
    }

    private fun startCrossfade(primary: ExoPlayer, nextIndex: Int, nextItem: MediaItem, durationMs: Long) {
        val secondary = crossfadePlayer ?: return
        cancelCrossfade(restorePrimaryVolume = false)

        applyPlayerRuntimeSettings(secondary, handleAudioFocus = false)
        pendingCrossfadeMediaId = nextItem.mediaId
        pendingCrossfadeIndex = nextIndex
        crossfadeHandoffInProgress = false
        crossfadeDurationMs = durationMs.coerceAtLeast(500L)
        crossfadeStartedAtMs = SystemClock.elapsedRealtime()
        crossfadeBaseVolume = primary.volume.coerceIn(0f, 1f).takeIf { it > 0.05f } ?: 1f

        runCatching {
            secondary.stop()
            secondary.clearMediaItems()
            secondary.repeatMode = Player.REPEAT_MODE_OFF
            secondary.shuffleModeEnabled = false
            secondary.setMediaItems(listOf(nextItem), 0, 0L)
            secondary.volume = 0f
            secondary.prepare()
            secondary.play()
        }.onFailure {
            cancelCrossfade(restorePrimaryVolume = true)
            return
        }

        startCrossfadeFadeLoop(primary, secondary)
    }

    private fun startCrossfadeFadeLoop(primary: ExoPlayer, secondary: ExoPlayer) {
        crossfadeFadeRunnable?.let { crossfadeHandler.removeCallbacks(it) }

        val fade = object : Runnable {
            override fun run() {
                if (pendingCrossfadeMediaId == null) return
                val sessionPlayer = mediaSession?.player as? ExoPlayer
                if (sessionPlayer !== primary || !primary.playWhenReady) {
                    cancelCrossfade(restorePrimaryVolume = true)
                    return
                }

                val duration = primary.duration
                val remaining = if (duration > 0L && duration != C.TIME_UNSET) {
                    (duration - primary.currentPosition).coerceAtLeast(0L)
                } else {
                    0L
                }
                if (remaining > crossfadeDurationMs + 900L) {
                    cancelCrossfade(restorePrimaryVolume = true)
                    return
                }
                if (remaining <= crossfadeHandoffLeadMs()) {
                    performCrossfadeHandoff(primary, secondary)
                    return
                }

                val elapsed = (SystemClock.elapsedRealtime() - crossfadeStartedAtMs).coerceAtLeast(0L)
                val linear = (elapsed.toFloat() / crossfadeDurationMs.toFloat()).coerceIn(0f, 1f)
                val eased = linear * linear * (3f - 2f * linear)

                primary.volume = (crossfadeBaseVolume * (1f - eased)).coerceIn(0f, 1f)
                secondary.volume = (crossfadeBaseVolume * eased).coerceIn(0f, 1f)

                val delayMs = if (linear >= 1f) 140L else 40L
                crossfadeHandler.postDelayed(this, delayMs)
            }
        }
        crossfadeFadeRunnable = fade
        crossfadeHandler.post(fade)
    }

    private fun handOffCrossfadeIfNeeded(primary: ExoPlayer, mediaItem: MediaItem): Boolean {
        val pendingId = pendingCrossfadeMediaId ?: return false
        if (mediaItem.mediaId != pendingId) return false

        val secondary = crossfadePlayer ?: return false
        return performCrossfadeHandoff(primary, secondary, preferredIndex = primary.currentMediaItemIndex)
    }

    private fun performCrossfadeHandoff(
        primary: ExoPlayer,
        secondary: ExoPlayer,
        preferredIndex: Int = C.INDEX_UNSET
    ): Boolean {
        val pendingId = pendingCrossfadeMediaId ?: return false
        if (crossfadeHandoffInProgress) return true

        val targetIndex = resolveCrossfadeTargetIndex(primary, pendingId, preferredIndex)
        if (targetIndex == C.INDEX_UNSET) {
            cancelCrossfade(restorePrimaryVolume = true)
            return false
        }

        crossfadeFadeRunnable?.let { crossfadeHandler.removeCallbacks(it) }
        crossfadeFadeRunnable = null

        val baseVolume = crossfadeBaseVolume.coerceIn(0f, 1f)
        val handoffPosition = compensatedHandoffPosition(secondary)
        secondary.volume = 0f
        pendingCrossfadeMediaId = null
        pendingCrossfadeIndex = C.INDEX_UNSET
        crossfadeHandoffInProgress = true

        runCatching {
            primary.seekTo(targetIndex, handoffPosition)
            if (primary.playbackState == Player.STATE_IDLE) {
                primary.prepare()
            }
            primary.volume = baseVolume
            primary.play()
        }.onFailure {
            crossfadeHandoffInProgress = false
            cancelCrossfade(restorePrimaryVolume = true)
            return false
        }

        releaseSecondaryAfterHandoff(primary, secondary, baseVolume)
        saveLastPlaybackState(primary)
        TrecMusicWidgetProvider.updateAll(this@PlaybackService, primary)
        return true
    }

    private fun resolveCrossfadeTargetIndex(
        primary: ExoPlayer,
        pendingId: String,
        preferredIndex: Int
    ): Int {
        fun valid(index: Int): Boolean {
            if (index == C.INDEX_UNSET || index !in 0 until primary.mediaItemCount) return false
            return runCatching { primary.getMediaItemAt(index).mediaId == pendingId }.getOrDefault(false)
        }

        if (valid(preferredIndex)) return preferredIndex
        if (valid(pendingCrossfadeIndex)) return pendingCrossfadeIndex
        return findMediaItemIndex(primary, pendingId)
    }

    private fun findMediaItemIndex(player: ExoPlayer, mediaId: String): Int {
        for (index in 0 until player.mediaItemCount) {
            val matches = runCatching { player.getMediaItemAt(index).mediaId == mediaId }.getOrDefault(false)
            if (matches) return index
        }
        return C.INDEX_UNSET
    }

    private fun compensatedHandoffPosition(secondary: ExoPlayer): Long {
        val elapsedSinceCrossfadeStart = (SystemClock.elapsedRealtime() - crossfadeStartedAtMs).coerceAtLeast(0L)
        val secondaryPosition = secondary.currentPosition.coerceAtLeast(0L)
        val trustedPosition = if (secondaryPosition + 700L < elapsedSinceCrossfadeStart) {
            elapsedSinceCrossfadeStart
        } else {
            secondaryPosition
        }
        val rawPosition = (trustedPosition + CROSSFADE_HANDOFF_LATENCY_COMP_MS).coerceAtLeast(0L)
        val duration = secondary.duration
        return if (duration > 0L && duration != C.TIME_UNSET) {
            rawPosition.coerceAtMost((duration - 250L).coerceAtLeast(0L))
        } else {
            rawPosition
        }
    }

    private fun crossfadeHandoffLeadMs(): Long {
        return (crossfadeDurationMs / 5L)
            .coerceIn(CROSSFADE_HANDOFF_LEAD_MIN_MS, CROSSFADE_HANDOFF_LEAD_MAX_MS)
    }

    private fun releaseSecondaryAfterHandoff(primary: ExoPlayer, secondary: ExoPlayer, targetVolume: Float) {
        crossfadeFadeRunnable?.let { crossfadeHandler.removeCallbacks(it) }
        crossfadeFadeRunnable = null
        runCatching {
            secondary.volume = 0f
            secondary.pause()
            secondary.stop()
            secondary.clearMediaItems()
        }
        primary.volume = targetVolume
        crossfadeHandoffInProgress = false
        if (primary.isPlaying) startCrossfadeWatcher(primary)
    }

    private fun cancelCrossfade(restorePrimaryVolume: Boolean) {
        crossfadeFadeRunnable?.let { crossfadeHandler.removeCallbacks(it) }
        crossfadeFadeRunnable = null
        pendingCrossfadeMediaId = null
        pendingCrossfadeIndex = C.INDEX_UNSET
        crossfadeHandoffInProgress = false

        crossfadePlayer?.let { secondary ->
            runCatching {
                secondary.pause()
                secondary.stop()
                secondary.clearMediaItems()
                secondary.volume = 0f
            }
        }

        if (restorePrimaryVolume) {
            (mediaSession?.player as? ExoPlayer)?.volume = crossfadeBaseVolume.coerceIn(0f, 1f)
        }
    }

    private fun saveLastPlaybackState(player: Player?, overrideShuffleMode: Boolean? = null) {
        val mediaId = player?.currentMediaItem?.mediaId
            ?.takeIf { it.isNotBlank() }
            ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val queue = savedQueueFromPlayer(player).ifEmpty { listOf(trackFromUri(mediaId)) }
        val currentIndex = queue.indexOfFirst { it.uri.toString() == mediaId }
            .takeIf { it >= 0 }
            ?: player.currentMediaItemIndex.coerceAtLeast(0)
        val previous = prefs.getPlaybackState()
        prefs.savePlaybackState(
            SavedPlaybackState(
                currentUri = mediaId,
                positionMs = position,
                queue = queue,
                currentIndex = currentIndex,
                shuffleMode = overrideShuffleMode ?: previous?.shuffleMode ?: player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                playlistName = previous?.playlistName,
                source = previous?.source ?: "all",
                savedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun restoreQueueFromPrefs(player: Player, play: Boolean): Boolean {
        val resume = buildResumeItems()
        if (resume.mediaItems.isEmpty()) return false
        val saved = prefs.getPlaybackState()
        player.shuffleModeEnabled = false
        player.repeatMode = saved?.repeatMode ?: player.repeatMode
        player.setMediaItems(resume.mediaItems, resume.startIndex, resume.startPositionMs)
        player.prepare()
        if (play) player.play()
        saveLastPlaybackState(player, overrideShuffleMode = saved?.shuffleMode)
        return true
    }

    private fun buildResumeItems(): MediaItemsWithStartPosition {
        val saved = prefs.getPlaybackState()
        val lastUri = saved?.currentUri ?: prefs.getLastTrackUri()
        val lastPosition = saved?.positionMs ?: prefs.getLastTrackPos().coerceAtLeast(0L)
        val cacheByUri = prefs.getTrackCache().distinctBy { it.uri.toString() }.associateBy { it.uri.toString() }
        val tracks = when {
            saved?.queue?.isNotEmpty() == true -> saved.queue.map { cacheByUri[it.uri.toString()] ?: it }
            cacheByUri.isNotEmpty() -> cacheByUri.values.toList()
            else -> lastUri?.let { listOf(trackFromUri(it)) } ?: emptyList()
        }.distinctBy { it.uri.toString() }
        if (tracks.isEmpty()) {
            return MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        }

        val startIndex = lastUri
            ?.let { uri -> tracks.indexOfFirst { it.uri.toString() == uri } }
            ?.takeIf { it >= 0 }
            ?: 0

        return MediaItemsWithStartPosition(
            tracks.map { buildServiceMediaItem(it) },
            startIndex,
            lastPosition
        )
    }

    private fun savedQueueFromPlayer(player: Player): List<TrecTrackEnhanced> {
        val currentIndex = player.currentMediaItemIndex
        val currentDuration = player.duration.takeIf { it > 0L } ?: 0L
        return orderedMediaItemIndices(player).mapNotNull { index ->
            runCatching {
                mediaItemToTrack(
                    player.getMediaItemAt(index),
                    fallbackDurationMs = if (index == currentIndex) currentDuration else 0L
                )
            }.getOrNull()
        }.distinctBy { it.uri.toString() }
    }

    private fun orderedMediaItemIndices(player: Player): List<Int> {
        val count = player.mediaItemCount
        if (count <= 0) return emptyList()
        if (!player.shuffleModeEnabled) return (0 until count).toList()

        val timeline = player.currentTimeline
        if (timeline.isEmpty) return (0 until count).toList()

        val result = ArrayList<Int>(count)
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && result.size < count) {
            result.add(index)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return if (result.size == count) result else (0 until count).toList()
    }

    private fun mediaItemToTrack(mediaItem: MediaItem, fallbackDurationMs: Long = 0L): TrecTrackEnhanced {
        val uri = mediaItem.localConfiguration?.uri ?: android.net.Uri.parse(mediaItem.mediaId)
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

    private fun buildServiceMediaItem(track: TrecTrackEnhanced): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.getDisplayArtist())
            .setAlbumTitle(track.album)

        val coverUrl = prefs.getCachedCoverUrl(coverCacheKey(track.artist, track.title, track.album))
        if (!coverUrl.isNullOrBlank()) {
            metadataBuilder.setArtworkUri(android.net.Uri.parse(coverUrl))
        }

        return MediaItem.Builder()
            .setMediaId(track.uri.toString())
            .setUri(track.uri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun coverCacheKey(artist: String?, title: String?, album: String?): String {
        fun n(v: String?): String {
            return v
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.lowercase(java.util.Locale.ROOT)
                .orEmpty()
        }
        return listOf(n(artist), n(title), n(album)).joinToString("|")
    }

    private fun trackFromUri(uriString: String): TrecTrackEnhanced {
        val uri = android.net.Uri.parse(uriString)
        val title = uri.lastPathSegment
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "TREC MUSIC"
        return TrecTrackEnhanced(uri = uri, title = title)
    }

    private fun Int.isPlayLikeCommand(): Boolean {
        return this == KeyEvent.KEYCODE_MEDIA_PLAY ||
            this == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            this == KeyEvent.KEYCODE_HEADSETHOOK
    }
}

