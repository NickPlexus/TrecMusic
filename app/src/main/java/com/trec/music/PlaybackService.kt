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
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.trec.music.utils.TrecAudioProcessor

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var prefs: PrefsManager
    private var listenStartElapsedMs: Long? = null

    // Наш кастомный процессор (храним ссылку, чтобы менять параметры)
    private val trecAudioProcessor = TrecAudioProcessor()

    companion object {
        const val CMD_UPDATE_SETTINGS = "TREC_UPDATE_SETTINGS"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        setMediaNotificationProvider(GlassMediaNotificationProvider(this))

        // --- 1. INITIAL CONFIG LOAD ---
        // Загружаем сохраненные настройки DSP сразу в процессор
        trecAudioProcessor.balance = prefs.getAudioBalance()
        trecAudioProcessor.isMono = prefs.getMonoAudio()

        // --- 2. RENDERERS FACTORY ---
        // Создаем фабрику, которая внедряет наш AudioProcessor в цепочку
        val renderersFactory = try {
            object : DefaultRenderersFactory(this) {
                // ФИКС: Убрали аргумент enableOffload, так как компилятор просит 3 аргумента
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    // Встраиваем наш процессор в DefaultAudioSink
                    return DefaultAudioSink.Builder()
                        .setAudioProcessors(arrayOf(trecAudioProcessor))
                        .setEnableFloatOutput(false) // Отключаем Float для совместимости
                        .build()
                }
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
        } catch (e: Exception) {
            DefaultRenderersFactory(this)
        }

        // 3. Атрибуты аудио
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Читаем настройки плеера
        val skipSilence = prefs.getSkipSilence()
        val ignoreFocus = prefs.getAudioFocusIgnore()

        // 4. Создание плеера
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, !ignoreFocus) // handleAudioFocus = !ignore
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSkipSilenceEnabled(skipSilence)
            .build()

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
                } else {
                    commitListeningTime()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
                prefs.incrementTracksStarted()
                prefs.incrementTrackPlayCount(mediaItem.mediaId)
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
                        .build()

                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(availableSessionCommands)
                        .setSessionExtras(sessionExtras)
                        .build()
                }

                // Обработка команд от ViewModel (Settings)
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CMD_UPDATE_SETTINGS) {
                        val p = session.player as? ExoPlayer

                        // Обновляем параметры ExoPlayer
                        p?.let {
                            it.skipSilenceEnabled = prefs.getSkipSilence()

                            // Audio Focus (требует переустановки атрибутов)
                            val ignore = prefs.getAudioFocusIgnore()
                            it.setAudioAttributes(it.audioAttributes, !ignore)
                        }

                        // Обновляем параметры DSP Процессора
                        trecAudioProcessor.balance = prefs.getAudioBalance()
                        trecAudioProcessor.isMono = prefs.getMonoAudio()

                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
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
        super.onDestroy()
    }
}

