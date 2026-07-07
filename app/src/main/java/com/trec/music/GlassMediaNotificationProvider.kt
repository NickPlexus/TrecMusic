package com.trec.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import java.net.URL

@UnstableApi
class GlassMediaNotificationProvider(private val context: Context) : MediaNotification.Provider {

    companion object {
        private const val CHANNEL_ID = "trec_playback_silent_v2"
        private const val NOTIFICATION_ID = 1102
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var pendingBitmapCallback: BitmapCallback? = null
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val artworkRequestId = AtomicInteger(0)

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
        val repeatMode = player.repeatMode
        val repeatBg = if (repeatMode == Player.REPEAT_MODE_OFF) {
            R.drawable.bg_notification_button
        } else {
            R.drawable.bg_notification_button_primary
        }
        val repeatTitle = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> "Повтор одного"
            Player.REPEAT_MODE_ALL -> "Повтор очереди"
            else -> "Повтор выключен"
        }

        val compactView = RemoteViews(context.packageName, R.layout.notification_glass)
        val bigView = RemoteViews(context.packageName, R.layout.notification_glass_big)

        bindCommonViews(compactView, title, subtitle, playRes, playBg, repeatBg, repeatMode)
        bindCommonViews(bigView, title, subtitle, playRes, playBg, repeatBg, repeatMode)

        val canPrev = player.availableCommands.containsAny(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        )
        val canNext = player.availableCommands.containsAny(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        )
        val repeatAction = actionFactory.createCustomAction(
            mediaSession,
            IconCompat.createWithResource(context, R.drawable.ic_notif_repeat),
            repeatTitle,
            PlaybackService.CMD_TOGGLE_REPEAT,
            Bundle.EMPTY
        )

        bindControls(compactView, canPrev, canNext, repeatAction, actionFactory, mediaSession)
        bindControls(bigView, canPrev, canNext, repeatAction, actionFactory, mediaSession)

        // Важно: системный QS/локскрин в новых Android почти всегда показывает НЕ RemoteViews,
        // а MediaStyle + Notification actions. Поэтому добавляем actions, чтобы изменения были видны.
        val actions = ArrayList<NotificationCompat.Action>(4)
        actions.add(repeatAction)

        if (canPrev) {
            val prevCmd =
                if (player.availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                } else {
                    Player.COMMAND_SEEK_TO_PREVIOUS
                }
            actions.add(
                actionFactory.createMediaAction(
                    mediaSession,
                    IconCompat.createWithResource(context, R.drawable.ic_notif_prev),
                    "Предыдущий",
                    prevCmd
                )
            )
        }

        actions.add(
            actionFactory.createMediaAction(
                mediaSession,
                IconCompat.createWithResource(context, playRes),
                if (showPause) "Пауза" else "Воспроизвести",
                Player.COMMAND_PLAY_PAUSE
            )
        )

        if (canNext) {
            val nextCmd =
                if (player.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                } else {
                    Player.COMMAND_SEEK_TO_NEXT
                }
            actions.add(
                actionFactory.createMediaAction(
                    mediaSession,
                    IconCompat.createWithResource(context, R.drawable.ic_notif_next),
                    "Следующий",
                    nextCmd
                )
            )
        }

        val style = MediaStyleNotificationHelper.DecoratedMediaCustomViewStyle(mediaSession)
        // System media notifications can show only 3 compact actions. Keep repeat visible.
        when (actions.size) {
            4 -> style.setShowActionsInCompactView(0, 2, 3)
            3 -> style.setShowActionsInCompactView(0, 1, 2)
            2 -> style.setShowActionsInCompactView(0, 1)
            1 -> style.setShowActionsInCompactView(0)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.media3_notification_small_icon)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(mediaSession.sessionActivity)
            .setDeleteIntent(
                actionFactory.createMediaActionPendingIntent(
                    mediaSession,
                    Player.COMMAND_STOP
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
            .setStyle(style)

        // Дублируем действия в Notification actions (их показывают QS/локскрин).
        actions.forEach { builder.addAction(it) }

        val currentItemUri = player.currentMediaItem?.localConfiguration?.uri
        val cachedArtworkUri = cachedArtworkUri(metadata)
        val fallbackArtworkUris = listOfNotNull(metadata.artworkUri, cachedArtworkUri, currentItemUri)
            .distinctBy { it.toString() }
        val bitmapFuture = mediaSession.bitmapLoader.loadBitmapFromMetadata(metadata)
        if (bitmapFuture != null) {
            pendingBitmapCallback?.discardIfPending()
            if (bitmapFuture.isDone) {
                try {
                    val bitmap = Futures.getDone(bitmapFuture)
                    if (bitmap != null) {
                        applyArtwork(bitmap, compactView, bigView, builder)
                    } else if (!loadArtworkAsync(fallbackArtworkUris, compactView, bigView, builder, onNotificationChangedCallback)) {
                        applyArtwork(null, compactView, bigView, builder)
                    }
                } catch (_: Exception) {
                    if (!loadArtworkAsync(fallbackArtworkUris, compactView, bigView, builder, onNotificationChangedCallback)) {
                        applyArtwork(null, compactView, bigView, builder)
                    }
                }
            } else {
                val handler = Handler(player.applicationLooper)
                val callback = BitmapCallback(
                    builder,
                    compactView,
                    bigView,
                    fallbackArtworkUris,
                    onNotificationChangedCallback
                )
                pendingBitmapCallback = callback
                Futures.addCallback(bitmapFuture, callback, handler::post)
            }
        } else {
            if (!loadArtworkAsync(fallbackArtworkUris, compactView, bigView, builder, onNotificationChangedCallback)) {
                applyArtwork(null, compactView, bigView, builder)
            }
        }

        return MediaNotification(NOTIFICATION_ID, builder.build())
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean {
        return false
    }

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo {
        return MediaNotification.Provider.NotificationChannelInfo(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_playback)
        )
    }

    private fun bindCommonViews(
        views: RemoteViews,
        title: String,
        subtitle: String,
        playRes: Int,
        playBg: Int,
        repeatBg: Int,
        repeatMode: Int
    ) {
        views.setTextViewText(R.id.notif_title, title)
        views.setTextViewText(R.id.notif_subtitle, subtitle)
        views.setImageViewResource(R.id.notif_play, playRes)
        views.setInt(R.id.notif_play, "setBackgroundResource", playBg)
        views.setImageViewResource(R.id.notif_repeat, R.drawable.ic_notif_repeat)
        views.setInt(R.id.notif_repeat, "setBackgroundResource", repeatBg)
        views.setInt(R.id.notif_repeat, "setImageAlpha", if (repeatMode == Player.REPEAT_MODE_OFF) 150 else 255)
    }

    private fun bindControls(
        views: RemoteViews,
        canPrev: Boolean,
        canNext: Boolean,
        repeatAction: NotificationCompat.Action,
        actionFactory: MediaNotification.ActionFactory,
        mediaSession: MediaSession
    ) {
        val playIntent =
            actionFactory.createMediaActionPendingIntent(
                mediaSession,
                Player.COMMAND_PLAY_PAUSE
            )
        views.setOnClickPendingIntent(R.id.notif_play, playIntent)
        repeatAction.actionIntent?.let { views.setOnClickPendingIntent(R.id.notif_repeat, it) }

        if (canPrev) {
            views.setViewVisibility(R.id.notif_prev, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.notif_prev,
                actionFactory.createMediaActionPendingIntent(
                    mediaSession,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
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
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
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

    private fun loadArtworkAsync(
        uris: List<Uri>,
        compactView: RemoteViews,
        bigView: RemoteViews,
        builder: NotificationCompat.Builder,
        callback: MediaNotification.Provider.Callback
    ): Boolean {
        if (uris.isEmpty()) return false
        val requestId = artworkRequestId.incrementAndGet()
        artworkExecutor.execute {
            val bitmap = uris.firstNotNullOfOrNull { uri -> loadArtworkBitmap(uri) }
            Handler(Looper.getMainLooper()).post {
                if (requestId != artworkRequestId.get()) return@post
                applyArtwork(bitmap, compactView, bigView, builder)
                callback.onNotificationChanged(MediaNotification(NOTIFICATION_ID, builder.build()))
            }
        }
        return true
    }

    private fun loadArtworkBitmap(uri: Uri): Bitmap? {
        val scheme = uri.scheme?.lowercase()
        val decoded = when (scheme) {
            "http", "https" -> runCatching {
                URL(uri.toString()).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            else -> loadEmbeddedArtwork(uri)
                ?: runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
        } ?: return null

        return scaleArtwork(decoded)
    }

    private fun cachedArtworkUri(metadata: MediaMetadata): Uri? {
        val title = metadata.title?.toString()
        val artist = metadata.artist?.toString()
        val album = metadata.albumTitle?.toString()
        if (title.isNullOrBlank()) return null

        val url = PrefsManager(context).getCachedCoverUrl(coverCacheKey(artist, title, album))
        return url?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
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

    private fun loadEmbeddedArtwork(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val data = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleArtwork(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= 512) return bitmap
        val scale = 512f / maxSide.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channelName = context.getString(R.string.notification_channel_playback)
        val channel = NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        channel.setSound(null, null)
        channel.enableVibration(false)
        notificationManager.createNotificationChannel(channel)
    }

    private inner class BitmapCallback(
        private val builder: NotificationCompat.Builder,
        private val compactView: RemoteViews,
        private val bigView: RemoteViews,
        private val fallbackUris: List<Uri>,
        private val callback: MediaNotification.Provider.Callback
    ) : FutureCallback<Bitmap> {
        private var discarded = false

        fun discardIfPending() {
            discarded = true
        }

        override fun onSuccess(result: Bitmap?) {
            if (discarded) return
            if (result != null) {
                applyArtwork(result, compactView, bigView, builder)
                callback.onNotificationChanged(MediaNotification(NOTIFICATION_ID, builder.build()))
            } else if (!loadArtworkAsync(fallbackUris, compactView, bigView, builder, callback)) {
                applyArtwork(null, compactView, bigView, builder)
                callback.onNotificationChanged(MediaNotification(NOTIFICATION_ID, builder.build()))
            }
        }

        override fun onFailure(t: Throwable) {
            if (discarded) return
            if (!loadArtworkAsync(fallbackUris, compactView, bigView, builder, callback)) {
                applyArtwork(null, compactView, bigView, builder)
                callback.onNotificationChanged(MediaNotification(NOTIFICATION_ID, builder.build()))
            }
        }
    }
}
