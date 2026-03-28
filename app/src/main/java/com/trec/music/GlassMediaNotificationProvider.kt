package com.trec.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures

@UnstableApi
class GlassMediaNotificationProvider(private val context: Context) : MediaNotification.Provider {

    companion object {
        private const val CHANNEL_ID = "trec_playback"
        private const val NOTIFICATION_ID = 1102
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var pendingBitmapCallback: BitmapCallback? = null

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        ensureChannel()

        val player = mediaSession.player
        val metadata = if (player.isCommandAvailable(Player.COMMAND_GET_METADATA)) {
            player.mediaMetadata
        } else {
            MediaMetadata.Builder().build()
        }

        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "TREC Music"
        val subtitle = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: metadata.albumTitle?.toString().orEmpty()

        val showPause = player.isPlaying ||
                (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady)
        val playRes = if (showPause) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
        val playBg = if (showPause) {
            R.drawable.bg_notification_button_primary
        } else {
            R.drawable.bg_notification_button
        }

        val compactView = RemoteViews(context.packageName, R.layout.notification_glass)
        val bigView = RemoteViews(context.packageName, R.layout.notification_glass_big)

        bindCommonViews(compactView, title, subtitle, playRes, playBg)
        bindCommonViews(bigView, title, subtitle, playRes, playBg)

        val canPrev = player.availableCommands.containsAny(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        )
        val canNext = player.availableCommands.containsAny(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        )
        bindControls(compactView, canPrev, canNext, actionFactory, mediaSession)
        bindControls(bigView, canPrev, canNext, actionFactory, mediaSession)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.media3_notification_small_icon)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(mediaSession.sessionActivity)
            .setDeleteIntent(
                actionFactory.createMediaActionPendingIntent(
                    mediaSession,
                    Player.COMMAND_STOP.toLong()
                )
            )
            .setCustomContentView(compactView)
            .setCustomBigContentView(bigView)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setStyle(MediaStyleNotificationHelper.DecoratedMediaCustomViewStyle(mediaSession))

        val bitmapFuture = mediaSession.bitmapLoader.loadBitmapFromMetadata(metadata)
        if (bitmapFuture != null) {
            pendingBitmapCallback?.discardIfPending()
            if (bitmapFuture.isDone) {
                try {
                    val bitmap = Futures.getDone(bitmapFuture)
                    applyArtwork(bitmap, compactView, bigView, builder)
                } catch (_: Exception) {
                    applyArtwork(null, compactView, bigView, builder)
                }
            } else {
                val handler = Handler(player.applicationLooper)
                val callback = BitmapCallback(
                    builder,
                    compactView,
                    bigView,
                    onNotificationChangedCallback
                )
                pendingBitmapCallback = callback
                Futures.addCallback(bitmapFuture, callback, handler::post)
            }
        } else {
            applyArtwork(null, compactView, bigView, builder)
        }

        return MediaNotification(NOTIFICATION_ID, builder.build())
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean {
        return false
    }

    private fun bindCommonViews(
        views: RemoteViews,
        title: String,
        subtitle: String,
        playRes: Int,
        playBg: Int
    ) {
        views.setTextViewText(R.id.notif_title, title)
        views.setTextViewText(R.id.notif_subtitle, subtitle)
        views.setImageViewResource(R.id.notif_play, playRes)
        views.setInt(R.id.notif_play, "setBackgroundResource", playBg)
    }

    private fun bindControls(
        views: RemoteViews,
        canPrev: Boolean,
        canNext: Boolean,
        actionFactory: MediaNotification.ActionFactory,
        mediaSession: MediaSession
    ) {
        val playIntent =
            actionFactory.createMediaActionPendingIntent(
                mediaSession,
                Player.COMMAND_PLAY_PAUSE.toLong()
            )
        views.setOnClickPendingIntent(R.id.notif_play, playIntent)

        if (canPrev) {
            views.setViewVisibility(R.id.notif_prev, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.notif_prev,
                actionFactory.createMediaActionPendingIntent(
                    mediaSession,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM.toLong()
                )
            )
        } else {
            views.setViewVisibility(R.id.notif_prev, View.INVISIBLE)
        }

        if (canNext) {
            views.setViewVisibility(R.id.notif_next, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.notif_next,
                actionFactory.createMediaActionPendingIntent(
                    mediaSession,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM.toLong()
                )
            )
        } else {
            views.setViewVisibility(R.id.notif_next, View.INVISIBLE)
        }
    }

    private fun applyArtwork(
        bitmap: Bitmap?,
        compactView: RemoteViews,
        bigView: RemoteViews,
        builder: NotificationCompat.Builder
    ) {
        if (bitmap != null) {
            compactView.setImageViewBitmap(R.id.notif_art, bitmap)
            bigView.setImageViewBitmap(R.id.notif_art, bitmap)
            builder.setLargeIcon(bitmap)
        } else {
            compactView.setImageViewResource(R.id.notif_art, R.drawable.notification_art_placeholder)
            bigView.setImageViewResource(R.id.notif_art, R.drawable.notification_art_placeholder)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channelName = context.getString(R.string.notification_channel_playback)
        val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        notificationManager.createNotificationChannel(channel)
    }

    private inner class BitmapCallback(
        private val builder: NotificationCompat.Builder,
        private val compactView: RemoteViews,
        private val bigView: RemoteViews,
        private val callback: MediaNotification.Provider.Callback
    ) : FutureCallback<Bitmap> {
        private var discarded = false

        fun discardIfPending() {
            discarded = true
        }

        override fun onSuccess(result: Bitmap?) {
            if (discarded) return
            applyArtwork(result, compactView, bigView, builder)
            callback.onNotificationChanged(MediaNotification(NOTIFICATION_ID, builder.build()))
        }

        override fun onFailure(t: Throwable) {
            // ignore artwork failure
        }
    }
}
