package app.iduanju.ui.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val CHANNEL = "iduanju_playback"
private const val NOTIFICATION_ID = 0x1D0A

class PlayerMediaSession(
    context: Context,
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrev: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
) {
    private val appContext = context.applicationContext
    private val session = MediaSession(appContext, "iDuanJuPlayer")
    private val notifications = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val playPauseAction = "${appContext.packageName}.playback.TOGGLE"
    private val prevAction = "${appContext.packageName}.playback.PREV"
    private val nextAction = "${appContext.packageName}.playback.NEXT"
    private var metadata = MediaMetadata.Builder().build()
    private var coverUrl: String? = null
    private var cover: Bitmap? = null
    private var coverJob: Job? = null
    private var isPlaying = false
    private var actions = 0L
    private var released = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (released) return
            when (intent.action) {
                playPauseAction -> onPlayPause()
                prevAction -> if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) onPrev()
                nextAction -> if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) onNext()
            }
        }
    }

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (!released && !isPlaying) onPlayPause() }
            override fun onPause() { if (!released && isPlaying) onPlayPause() }
            override fun onSkipToNext() { if (!released) onNext() }
            override fun onSkipToPrevious() { if (!released) onPrev() }
            override fun onSeekTo(pos: Long) { if (!released) this@PlayerMediaSession.onSeekTo(pos) }
        }, Handler(Looper.getMainLooper()))
        session.isActive = true
        val filter = IntentFilter(playPauseAction).apply { addAction(prevAction); addAction(nextAction) }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            })
        }
    }

    fun updateMetadata(title: String, subtitle: String, coverUrl: String?, durationMs: Long) {
        if (released) return
        val url = coverUrl?.takeUnless { it.isBlank() }
        val urlChanged = url != this.coverUrl
        if (urlChanged) {
            coverJob?.cancel()
            this.coverUrl = url
            cover = null
        }
        publishMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0L)))
        if (urlChanged && url != null) coverJob = scope.launch {
            cover = loadCover(url) ?: return@launch
            publishMetadata(MediaMetadata.Builder(metadata))
        }
    }

    private fun publishMetadata(builder: MediaMetadata.Builder) {
        cover?.let { builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it).putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        metadata = builder.build()
        session.setMetadata(metadata)
        updateNotification()
    }

    private suspend fun loadCover(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(appContext).data(url).size(320, 320).scale(Scale.FIT).allowHardware(false).build()
        val drawable = (appContext.imageLoader.execute(request) as? SuccessResult)?.drawable ?: return@withContext null
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = (320f / maxOf(width, height)).coerceAtMost(1f)
        runCatching { drawable.toBitmap((width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1)) }.getOrNull()
    }

    fun updatePlaybackState(isPlaying: Boolean, positionMs: Long, speed: Float, hasPrev: Boolean, hasNext: Boolean) {
        if (released) return
        this.isPlaying = isPlaying
        actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or (if (hasPrev) PlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L) or
            (if (hasNext) PlaybackState.ACTION_SKIP_TO_NEXT else 0L)
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(state, positionMs.coerceAtLeast(0L), if (isPlaying) speed else 0f).build())
        updateNotification()
    }

    private fun updateNotification() = runCatching {
        if (actions == 0L) return@runCatching
        val builder = Notification.Builder(appContext, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
            .setContentText(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
            .setCategory(Notification.CATEGORY_TRANSPORT).setOngoing(isPlaying).setOnlyAlertOnce(true).setShowWhen(false)
        cover?.let { builder.setLargeIcon(it) }
        var count = 0
        fun add(icon: Int, label: String, action: String) {
            val intent = Intent(action).setPackage(appContext.packageName)
            val pending = PendingIntent.getBroadcast(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(Icon.createWithResource("android", icon), label, pending).build())
            count++
        }
        if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) add(android.R.drawable.ic_media_previous, "Previous", prevAction)
        add(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pause" else "Play", playPauseAction)
        if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) add(android.R.drawable.ic_media_next, "Next", nextAction)
        builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*IntArray(count) { it }))
        notifications.notify(NOTIFICATION_ID, builder.build())
    }

    fun release() {
        if (released) return
        released = true
        scope.cancel()
        runCatching { notifications.cancel(NOTIFICATION_ID) }
        runCatching { appContext.unregisterReceiver(receiver) }
        session.isActive = false
        session.release()
    }
}
