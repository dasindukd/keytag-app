package com.keytag.app

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Android keeps this service running once "Notification access" is allowed.
 * It watches whatever is playing (Brave, YouTube, YT Music...) and sends
 * title + picture to the keytag.
 */
class MediaListenerService : NotificationListenerService() {

    companion object {
        @Volatile var instance: MediaListenerService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var ble: KeytagBle? = null
    private var lastMeta = ""
    private var lastArtKey = ""

    private val component by lazy { ComponentName(this, MediaListenerService::class.java) }

    private val controllerCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push(false)
        override fun onPlaybackStateChanged(state: PlaybackState?) = push(false)
        override fun onSessionDestroyed() = pickController(null)
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    // re-check every 10 s: switch to whichever app is playing, re-sync the progress bar
    private val tick = object : Runnable {
        override fun run() {
            pickController(null)
            lastMeta = ""            // force a meta resend
            push(false)
            main.postDelayed(this, 10_000)
        }
    }

    override fun onListenerConnected() {
        instance = this
        sessions = getSystemService(MediaSessionManager::class.java)
        try {
            sessions?.addOnActiveSessionsChangedListener(sessionsListener, component, main)
        } catch (e: SecurityException) {
            Status.connection = "Notification access needed (step 2)"
        }
        ble?.stop()                      // in case Android connects us twice
        ble = KeytagBle(this) { push(true) }.also { it.start() }
        pickController(null)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 10_000)
    }

    override fun onListenerDisconnected() {
        main.removeCallbacksAndMessages(null)
        try { sessions?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(controllerCb)
        controller = null
        ble?.stop()
        ble = null
        instance = null
        Status.connection = "Stopped"
    }

    fun reconnect() {
        main.post { ble?.reconnect() }
    }

    private fun pickController(list: List<MediaController>?) {
        val all = list ?: try {
            sessions?.getActiveSessions(component)
        } catch (e: SecurityException) {
            null
        } ?: emptyList()

        val best = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controller?.takeIf { c -> all.any { it.sessionToken == c.sessionToken } }
            ?: all.firstOrNull()

        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCb)
            controller = best
            best?.registerCallback(controllerCb, main)
            push(true)
        }
    }

    private fun push(force: Boolean) {
        val b = ble ?: return
        val c = controller
        if (c == null) {
            Status.nowPlaying = "Nothing playing"
            return
        }
        val m = c.metadata
        val s = c.playbackState

        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val playing = s?.state == PlaybackState.STATE_PLAYING
        var pos = s?.position ?: 0L
        if (playing && s != null && s.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        val dur = (m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)

        Status.nowPlaying = if (title.isEmpty()) "Nothing playing" else "$title - $artist"

        val meta = "${if (playing) 1 else 0},${pos.coerceAtLeast(0)},$dur\n${title.take(100)}\n${artist.take(60)}"
        if (force || meta != lastMeta) {
            lastMeta = meta
            b.sendMeta(meta.toByteArray(Charsets.UTF_8))
        }

        val bmp: Bitmap? = m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artKey = "$title|$artist|${bmp?.generationId}|${bmp?.width}"
        if (force || artKey != lastArtKey) {
            lastArtKey = artKey
            b.sendArt(bmp?.let { ArtProcessor.toMono64(it) })
        }
    }
}
