package com.ridevoice.qq

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

class MusicAlarm(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val am = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var player: MediaPlayer? = null
    private var callback: (() -> Unit)? = null
    private var finished = true
    private var focusRequest: AudioFocusRequest? = null

    private val timeout = Runnable { finish() }

    fun play(uriString: String, maxMs: Long, onDone: () -> Unit) {
        main.post {
            stopInternal()
            if (uriString.isBlank()) {
                onDone()
                return@post
            }
            finished = false
            callback = onDone
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            try {
                mediaPlayer.setAudioAttributes(attrs)
                mediaPlayer.setDataSource(context, Uri.parse(uriString))
                mediaPlayer.isLooping = false
                mediaPlayer.setOnPreparedListener {
                    if (finished) return@setOnPreparedListener
                    requestFocus()
                    it.start()
                    main.postDelayed(timeout, maxMs)
                }
                mediaPlayer.setOnCompletionListener {
                    main.post { finish() }
                }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    main.post { finish() }
                    true
                }
                mediaPlayer.prepareAsync()
            } catch (_: Exception) {
                finish()
            }
        }
    }

    fun stop() {
        main.post { stopInternal() }
    }

    private fun finish() {
        if (finished) return
        finished = true
        main.removeCallbacks(timeout)
        releasePlayer()
        abandonFocus()
        val done = callback
        callback = null
        done?.invoke()
    }

    private fun stopInternal() {
        finished = true
        main.removeCallbacks(timeout)
        callback = null
        releasePlayer()
        abandonFocus()
    }

    private fun releasePlayer() {
        val mediaPlayer = player ?: return
        player = null
        try {
            mediaPlayer.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer.reset()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer.release()
        } catch (_: Exception) {
        }
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        try {
            am?.requestAudioFocus(request)
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        try {
            am?.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }
}
