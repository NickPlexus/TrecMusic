package com.trec.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken

class TrecMusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds, null)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateWidgets(context, appWidgetManager, intArrayOf(appWidgetId), null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY_PAUSE,
            ACTION_PREVIOUS,
            ACTION_NEXT,
            ACTION_SHUFFLE,
            ACTION_REPEAT -> handleTransportAction(context.applicationContext, intent.action!!)
            ACTION_REFRESH -> updateAll(context.applicationContext)
        }
    }

    private fun handleTransportAction(context: Context, action: String) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val executor = ContextCompat.getMainExecutor(context)
        future.addListener(
            {
                try {
                    val controller = future.get()
                    when (action) {
                        ACTION_PLAY_PAUSE -> {
                            if (controller.mediaItemCount == 0) {
                                restoreQueue(controller, play = true)
                                updateAll(context, controller)
                                return@addListener
                            }
                            if (controller.isPlaying) {
                                controller.pause()
                            } else {
                                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                                controller.play()
                            }
                        }
                        ACTION_PREVIOUS -> {
                            if (controller.mediaItemCount == 0) restoreQueue(controller, play = false)
                            if (controller.currentPosition > 3000L) {
                                controller.seekTo(0L)
                            } else if (controller.hasPreviousMediaItem()) {
                                controller.seekToPreviousMediaItem()
                            }
                        }
                        ACTION_NEXT -> {
                            if (controller.mediaItemCount == 0) restoreQueue(controller, play = false)
                            if (controller.hasNextMediaItem()) {
                                controller.seekToNextMediaItem()
                            }
                        }
                        ACTION_REPEAT -> {
                            controller.sendCustomCommand(
                                SessionCommand(PlaybackService.CMD_TOGGLE_REPEAT, Bundle.EMPTY),
                                Bundle.EMPTY
                            )
                        }
                        ACTION_SHUFFLE -> {
                            controller.sendCustomCommand(
                                SessionCommand(PlaybackService.CMD_TOGGLE_SHUFFLE, Bundle.EMPTY),
                                Bundle.EMPTY
                            )
                        }
                    }
                    updateAll(context, controller)
                } catch (_: Throwable) {
                    updateAll(context)
                } finally {
                    MediaController.releaseFuture(future)
                }
            },
            executor
        )
    }

    companion object {
        private const val ACTION_PLAY_PAUSE = "com.trec.music.widget.PLAY_PAUSE"
        private const val ACTION_PREVIOUS = "com.trec.music.widget.PREVIOUS"
        private const val ACTION_NEXT = "com.trec.music.widget.NEXT"
        private const val ACTION_SHUFFLE = "com.trec.music.widget.SHUFFLE"
        private const val ACTION_REPEAT = "com.trec.music.widget.REPEAT"
        private const val ACTION_REFRESH = "com.trec.music.widget.REFRESH"

        private fun restoreQueue(controller: MediaController, play: Boolean) {
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_RESTORE_QUEUE, Bundle.EMPTY),
                Bundle().apply { putBoolean("play", play) }
            )
        }

        fun updateAll(context: Context, player: Player? = null) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, TrecMusicWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(component)
            updateWidgets(context, appWidgetManager, ids, player)
        }

        private fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            player: Player?
        ) {
            if (appWidgetIds.isEmpty()) return
            appWidgetIds.forEach { id ->
                val options = appWidgetManager.getAppWidgetOptions(id)
                appWidgetManager.updateAppWidget(id, buildViews(context, player, options))
            }
        }

        private fun buildViews(context: Context, player: Player?, options: Bundle = Bundle.EMPTY): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_trec_music)
            val metadata = player?.mediaMetadata
            val prefs = PrefsManager(context)
            val savedState = prefs.getPlaybackState()
            val fallbackTitle = prefs.getLastTrackUri()
                ?.let { Uri.parse(it).lastPathSegment }
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() }

            val title = metadata?.title?.toString()?.takeIf { it.isNotBlank() }
                ?: fallbackTitle
                ?: "TREC MUSIC"
            val subtitle = metadata?.artist?.toString()?.takeIf { it.isNotBlank() }
                ?: metadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                ?: if (fallbackTitle != null) "Последний трек готов" else "Откройте приложение"

            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_subtitle, subtitle)
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (player?.isPlaying == true) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
            )
            val shuffleActive = savedState?.shuffleMode ?: (player?.shuffleModeEnabled == true)
            val repeatMode = player?.repeatMode ?: savedState?.repeatMode ?: Player.REPEAT_MODE_OFF
            views.setImageViewResource(R.id.widget_shuffle, R.drawable.ic_widget_shuffle)
            views.setInt(
                R.id.widget_shuffle,
                "setBackgroundResource",
                if (shuffleActive) R.drawable.bg_widget_button_primary else R.drawable.bg_widget_button
            )
            views.setInt(R.id.widget_shuffle, "setImageAlpha", if (shuffleActive) 255 else 150)
            views.setImageViewResource(R.id.widget_repeat, R.drawable.ic_notif_repeat)
            views.setInt(
                R.id.widget_repeat,
                "setBackgroundResource",
                if (repeatMode == Player.REPEAT_MODE_OFF) R.drawable.bg_widget_button else R.drawable.bg_widget_button_primary
            )
            views.setInt(R.id.widget_repeat, "setImageAlpha", if (repeatMode == Player.REPEAT_MODE_OFF) 150 else 255)

            val artworkUri = metadata?.artworkUri
            if (artworkUri != null) {
                views.setImageViewUri(R.id.widget_art, artworkUri)
            } else {
                views.setImageViewResource(R.id.widget_art, R.drawable.notification_art_placeholder)
            }

            val duration = player?.duration?.takeIf { it > 0L } ?: 0L
            val position = player?.currentPosition?.takeIf { it > 0L } ?: 0L
            if (duration > 0L) {
                val progress = ((position.coerceAtMost(duration) * 100L) / duration).toInt()
                views.setProgressBar(R.id.widget_progress, 100, progress.coerceIn(0, 100), false)
            } else {
                views.setProgressBar(R.id.widget_progress, 100, 0, false)
            }

            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
            val compact = minHeight < 124 || minWidth < 220
            views.setViewVisibility(R.id.widget_art, if (compact) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_subtitle, if (compact) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_refresh, if (compact) View.GONE else View.VISIBLE)

            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                widgetIntent(context, ACTION_PLAY_PAUSE, 1)
            )
            views.setOnClickPendingIntent(R.id.widget_prev, widgetIntent(context, ACTION_PREVIOUS, 2))
            views.setOnClickPendingIntent(R.id.widget_next, widgetIntent(context, ACTION_NEXT, 3))
            views.setOnClickPendingIntent(R.id.widget_refresh, widgetIntent(context, ACTION_REFRESH, 4))
            views.setOnClickPendingIntent(R.id.widget_shuffle, widgetIntent(context, ACTION_SHUFFLE, 5))
            views.setOnClickPendingIntent(R.id.widget_repeat, widgetIntent(context, ACTION_REPEAT, 6))
            return views
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(context, 10, intent, pendingIntentFlags())
        }

        private fun widgetIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, TrecMusicWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(context, requestCode, intent, pendingIntentFlags())
        }

        private fun pendingIntentFlags(): Int {
            return PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        }
    }
}
