package com.cet6listening.app

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var stats: StatsStore
    private var itemsById: Map<String, ListeningItem> = emptyMap()

    private val handler = Handler(Looper.getMainLooper())
    private var lastPositionMs = 0L
    private var lastWallClockMs = 0L
    private var sessionSectionIndex = -1
    private var sessionSectionListenedMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            captureProgress()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            lastPositionMs = player.currentPosition.coerceAtLeast(0L)
            lastWallClockMs = if (player.isPlaying) SystemClock.elapsedRealtime() else 0L
            sessionSectionIndex = -1
            sessionSectionListenedMs = 0L
            mediaItem?.mediaId?.let(stats::setRecent)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            lastPositionMs = player.currentPosition.coerceAtLeast(0L)
            lastWallClockMs = if (isPlaying) SystemClock.elapsedRealtime() else 0L
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                captureProgress(forceEnd = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        stats = StatsStore(this)
        itemsById = CatalogRepository.load(this).associateBy { it.id }
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                setPlaybackSpeed(stats.playbackSpeed())
                addListener(playerListener)
            }
        session = MediaSession.Builder(this, player).build()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.playback_channel_name)
            .build()
            .apply { setSmallIcon(R.drawable.ic_notification) }
        setMediaNotificationProvider(notificationProvider)
        handler.post(tick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        currentItemId()?.let { stats.setLastPositionMs(it, player.currentPosition) }
        player.removeListener(playerListener)
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun currentItemId(): String? =
        player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }

    private fun captureProgress(forceEnd: Boolean = false) {
        val itemId = currentItemId() ?: return
        val item = itemsById[itemId] ?: return
        val now = SystemClock.elapsedRealtime()
        val position = player.currentPosition.coerceAtLeast(0L)
        val wallDelta = if (lastWallClockMs == 0L) 0L else (now - lastWallClockMs)

        if (player.isPlaying && !forceEnd) {
            if (lastWallClockMs > 0L && wallDelta in 1..MAX_WALL_DELTA_MS) {
                stats.addDailyListeningMs(wallDelta)
            }
            val mediaDelta = position - lastPositionMs
            val validForwardMovement = mediaDelta in 0..MAX_MEDIA_DELTA_MS
            if (validForwardMovement && mediaDelta > 0L) {
                addMediaSpan(item, lastPositionMs, position)
                trackSessionProgress(item, lastPositionMs, position)
            } else if (mediaDelta < 0L || mediaDelta > MAX_MEDIA_DELTA_MS) {
                resetSession(item, position)
            }
        } else if (forceEnd) {
            val mediaDelta = position - lastPositionMs
            if (mediaDelta in 1..MAX_MEDIA_DELTA_MS) {
                addMediaSpan(item, lastPositionMs, position)
                trackSessionProgress(item, lastPositionMs, position, forceEnd = true)
            } else if (mediaDelta !in -MAX_MEDIA_DELTA_MS..0L) {
                resetSession(item, position)
            } else if (sessionSectionIndex == item.sections.lastIndex) {
                maybeCountSection(item, sessionSectionIndex, sessionSectionListenedMs)
            }
        }

        lastPositionMs = position
        lastWallClockMs = if (player.isPlaying) now else 0L
        stats.setLastPositionMs(itemId, position)
    }

    private fun addMediaSpan(item: ListeningItem, startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        item.sections.forEachIndexed { index, section ->
            val overlapStart = maxOf(startMs, section.startMs)
            val overlapEnd = minOf(endMs, section.endMs)
            if (overlapEnd > overlapStart) {
                stats.addSectionListenedMs(item.id, index, overlapEnd - overlapStart)
            }
        }
    }

    private fun trackSessionProgress(
        item: ListeningItem,
        startMs: Long,
        endMs: Long,
        forceEnd: Boolean = false
    ) {
        if (sessionSectionIndex < 0) {
            sessionSectionIndex = item.sectionIndexAt(startMs)
            sessionSectionListenedMs = 0L
        }
        while (sessionSectionIndex < item.sections.size) {
            val index = sessionSectionIndex
            val section = item.sections[index]
            val overlapStart = maxOf(startMs, section.startMs)
            val overlapEnd = minOf(endMs, section.endMs)
            if (overlapEnd > overlapStart) {
                sessionSectionListenedMs += overlapEnd - overlapStart
            }
            val reachedEnd = forceEnd || endMs >= section.endMs - COUNT_EDGE_GRACE_MS
            if (!reachedEnd) break
            if (sessionSectionIndex == index && endMs >= section.endMs - COUNT_EDGE_GRACE_MS) {
                maybeCountSection(item, index, sessionSectionListenedMs)
            }
            if (index == item.sections.lastIndex) break
            sessionSectionIndex = index + 1
            sessionSectionListenedMs = maxOf(0L, endMs - item.sections[index + 1].startMs)
            if (endMs < item.sections[index + 1].endMs) break
        }
    }

    private fun resetSession(item: ListeningItem, positionMs: Long) {
        sessionSectionIndex = item.sectionIndexAt(positionMs)
        sessionSectionListenedMs = 0L
    }

    private fun maybeCountSection(item: ListeningItem, index: Int, listenedMs: Long) {
        val section = item.sections.getOrNull(index) ?: return
        if (listenedMs + COUNT_EDGE_GRACE_MS >= (section.durationMs * COMPLETION_RATIO).toLong()) {
            stats.incrementSectionPlayCount(item.id, index)
        }
    }

    companion object {
        private const val TICK_MS = 500L
        private const val MAX_WALL_DELTA_MS = 2_000L
        private const val MAX_MEDIA_DELTA_MS = 4_000L
        private const val COUNT_EDGE_GRACE_MS = 900L
        private const val COMPLETION_RATIO = 0.80
    }
}
