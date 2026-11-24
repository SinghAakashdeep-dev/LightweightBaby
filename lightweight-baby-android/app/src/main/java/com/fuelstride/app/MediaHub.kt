package com.fuelstride.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Base64
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Exists only so the user can grant "notification access", which lets us see what's playing. */
class MediaNotifListener : NotificationListenerService()

/**
 * Reads the phone's current music session (Spotify first) and sends play/pause/skip commands.
 * Without notification access it still controls playback through media keys, but can't show the song.
 */
class MediaHub(private val act: Activity, private val push: (String) -> Unit) {
    private val msm = act.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(act, MediaNotifListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var artKey = ""
    private var artData = ""
    private var listening = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = emit()
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit()
        override fun onSessionDestroyed() = pick()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    fun hasAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(act).contains(act.packageName)

    fun start() {
        if (hasAccess() && !listening) {
            try {
                msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                listening = true
            } catch (_: SecurityException) {}
        }
        pick()
    }

    fun stop() {
        if (listening) {
            try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
            listening = false
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun pick() {
        val sessions = if (hasAccess()) {
            try { msm.getActiveSessions(component) } catch (_: SecurityException) { emptyList() }
        } else emptyList()
        val c = sessions.firstOrNull { it.packageName == SPOTIFY && it.metadata != null }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.metadata != null }
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback, main)
        }
        emit()
    }

    fun emit() {
        val o = JSONObject().put("access", hasAccess())
        val c = controller
        if (c != null) {
            val m = c.metadata
            val s = c.playbackState
            val playing = s?.state == PlaybackState.STATE_PLAYING
            var pos = s?.position ?: 0L
            if (playing && s != null && s.lastPositionUpdateTime > 0) {
                pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
            }
            val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val key = "$title|$album"
            if (key != artKey) {
                artKey = key
                val bmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                artData = bmp?.let { encode(it) } ?: ""
            }
            o.put("app", appLabel(c.packageName)).put("pkg", c.packageName)
                .put("title", title)
                .put("artist", m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
                .put("album", album)
                .put("duration", m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
                .put("position", pos.coerceAtLeast(0))
                .put("playing", playing)
                .put("art", artData)
        }
        push(o.toString())
    }

    fun command(cmd: String, arg: Long) {
        val c = controller
        if (c != null) {
            val t = c.transportControls
            when (cmd) {
                "play" -> t.play()
                "pause" -> t.pause()
                "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                "next" -> t.skipToNext()
                "prev" -> t.skipToPrevious()
                "seek" -> t.seekTo(arg)
            }
            return
        }
        // No session visible: send a media key, which goes to the last music app used (usually Spotify)
        val code = when (cmd) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        main.postDelayed({ pick() }, 600)
    }

    private fun appLabel(pkg: String): String = try {
        act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun encode(src: Bitmap): String {
        val side = 240
        val scale = side.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object { const val SPOTIFY = "com.spotify.music" }
}
