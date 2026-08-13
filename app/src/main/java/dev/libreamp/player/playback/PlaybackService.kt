package dev.libreamp.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.session.MediaSession
import android.os.IBinder
import dev.libreamp.player.MainActivity
import dev.libreamp.player.R
import dev.libreamp.player.data.db.PlaylistEntryEntity
import dev.libreamp.player.data.db.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.random.Random

/**
 * Foreground service hosting the platform MediaSession and the MediaStyle
 * notification. Owns queue traversal (next/prev/shuffle/repeat); actual
 * decode/output lives in the shared PlaybackEngine (see PlaybackController).
 */
class PlaybackService : Service() {

    private lateinit var engine: PlaybackEngine
    private lateinit var sessionHolder: MediaSessionHolder
    private lateinit var focusManager: AudioFocusManager
    private lateinit var repository: PlaylistRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var queue: List<PlaylistEntryEntity> = emptyList()
    private var shuffledIndices: List<Int> = emptyList()
    private var currentQueuePos = -1 // index into `queue` or `shuffledIndices` depending on shuffle

    override fun onCreate() {
        super.onCreate()
        engine = PlaybackController.get(this)
        repository = PlaylistRepository(this)

        val callback = object : MediaSession.Callback() {
            override fun onPlay() { userPlay() }
            override fun onPause() { engine.pause() }
            override fun onSkipToNext() { skipNext() }
            override fun onSkipToPrevious() { skipPrevious() }
            override fun onSeekTo(pos: Long) { engine.seekTo(pos * 1000) }
        }
        sessionHolder = MediaSessionHolder(this, callback)
        focusManager = AudioFocusManager(this, engine, onLossStop = { stopPlaybackAndSelf() })

        engine.listener = object : PlaybackEngine.Listener {
            override fun onTrackCompleted() {
                val state = engine.state.value
                if (state.repeatMode == RepeatMode.ONE) {
                    state.entry?.let { engine.play(it, flushBuffered = false) }
                } else {
                    skipNext(autoAdvance = true)
                }
            }
            override fun onError(message: String) {
                skipNext(autoAdvance = true)
            }
        }

        engine.state.onEach { state ->
            sessionHolder.updateState(state)
            updateNotification(state)
        }.launchIn(scope)

        repository.observeAll().onEach { list ->
            queue = list
            reshuffleIfNeeded()
        }.launchIn(scope)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be synchronous, before any async work, to avoid the foreground-start timeout.
        startForeground(NOTIFICATION_ID, buildNotification(engine.state.value))

        when (intent?.action) {
            ACTION_PLAY -> userPlay()
            ACTION_PAUSE -> engine.pause()
            ACTION_NEXT -> skipNext()
            ACTION_PREV -> skipPrevious()
            ACTION_PLAY_ENTRY -> {
                val id = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
                val entry = queue.find { it.id == id }
                if (entry != null) {
                    currentQueuePos = indexInTraversal(entry)
                    startPlayback(entry)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        focusManager.abandonFocus()
        sessionHolder.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun userPlay() {
        val state = engine.state.value
        if (state.entry != null) {
            if (!focusManager.requestFocus()) return
            engine.resume()
        } else if (queue.isNotEmpty()) {
            if (!focusManager.requestFocus()) return
            currentQueuePos = 0
            startPlayback(traversalEntryAt(0))
        }
    }

    /**
     * [flushBuffered] false only for an auto-advance: there the audio still buffered is
     * the tail of the track that just finished and has to be heard, whereas a track the
     * user picked should cut straight over it.
     */
    private fun startPlayback(entry: PlaylistEntryEntity, flushBuffered: Boolean = true) {
        if (!focusManager.requestFocus()) return
        engine.play(entry, flushBuffered)
    }

    private fun skipNext(autoAdvance: Boolean = false) {
        if (queue.isEmpty()) { if (!autoAdvance) return else return }
        val state = engine.state.value
        val atEnd = currentQueuePos >= traversalSize() - 1
        if (atEnd) {
            if (state.repeatMode == RepeatMode.ALL) {
                currentQueuePos = 0
            } else {
                if (autoAdvance) { engine.stop(); stopPlaybackAndSelf() }
                return
            }
        } else {
            currentQueuePos++
        }
        startPlayback(traversalEntryAt(currentQueuePos), flushBuffered = !autoAdvance)
    }

    private fun skipPrevious() {
        if (queue.isEmpty()) return
        currentQueuePos = if (currentQueuePos <= 0) 0 else currentQueuePos - 1
        startPlayback(traversalEntryAt(currentQueuePos))
    }

    private fun reshuffleIfNeeded() {
        if (engine.state.value.shuffle) {
            shuffledIndices = queue.indices.shuffled(Random)
        }
    }

    private fun traversalSize() = queue.size

    private fun traversalEntryAt(pos: Int): PlaylistEntryEntity {
        val idx = if (engine.state.value.shuffle && shuffledIndices.size == queue.size) {
            shuffledIndices[pos]
        } else pos
        return queue[idx]
    }

    private fun indexInTraversal(entry: PlaylistEntryEntity): Int {
        val rawIndex = queue.indexOf(entry)
        if (!engine.state.value.shuffle) return rawIndex
        if (shuffledIndices.size != queue.size) shuffledIndices = queue.indices.shuffled(Random)
        return shuffledIndices.indexOf(rawIndex)
    }

    private fun stopPlaybackAndSelf() {
        engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_playback), NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(state: PlaybackState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: PlaybackState): Notification {
        fun actionIntent(action: String) = PendingIntent.getService(
            this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (state.isPlaying) {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Pause", actionIntent(ACTION_PAUSE)
            ).build()
        } else {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_play),
                "Play", actionIntent(ACTION_PLAY)
            ).build()
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.entry?.title ?: state.entry?.displayName ?: getString(R.string.app_name))
            .setContentText(state.entry?.artist ?: "")
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                    "Previous", actionIntent(ACTION_PREV)
                ).build()
            )
            .addAction(playPauseAction)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next),
                    "Next", actionIntent(ACTION_NEXT)
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(sessionHolder.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(state.isPlaying)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "dev.libreamp.player.action.PLAY"
        const val ACTION_PAUSE = "dev.libreamp.player.action.PAUSE"
        const val ACTION_NEXT = "dev.libreamp.player.action.NEXT"
        const val ACTION_PREV = "dev.libreamp.player.action.PREV"
        const val ACTION_PLAY_ENTRY = "dev.libreamp.player.action.PLAY_ENTRY"
        const val EXTRA_ENTRY_ID = "entry_id"
    }
}
