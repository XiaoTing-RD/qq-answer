package com.ridevoice.qq

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private data class Request(
        val text: String,
        val pitch: Float,
        val rate: Float,
        val onDone: () -> Unit
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val am = appContext.getSystemService(AudioManager::class.java)
    private val tts = TextToSpeech(appContext, this)
    private val pending = ArrayDeque<Request>()
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var initialized = false
    private var ready = false
    private var currentId: String? = null
    private var currentDone: (() -> Unit)? = null
    private var seq = 0
    private var focusRequest: AudioFocusRequest? = null

    private val watchdog = Runnable {
        val id = currentId ?: return@Runnable
        try {
            tts.stop()
        } catch (_: Exception) {
        }
        finish(id)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
            }

            override fun onDone(utteranceId: String?) {
                main.post { finish(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { finish(utteranceId) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
            }
        })
    }

    override fun onInit(status: Int) {
        main.post {
            initialized = true
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.setAudioAttributes(attrs)
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            }
            if (pending.isNotEmpty()) {
                play(pending.removeFirst())
            }
        }
    }

    fun speak(text: String, pitch: Float, rate: Float, onDone: () -> Unit = {}) {
        main.post {
            val request = Request(text, pitch, rate, onDone)
            when {
                ready -> play(request)
                initialized -> onDone()
                else -> pending.addLast(request)
            }
        }
    }

    private fun play(request: Request) {
        main.removeCallbacks(watchdog)
        currentDone = null
        currentId = null
        try {
            tts.stop()
        } catch (_: Exception) {
        }
        tts.setPitch(request.pitch.coerceIn(0.5f, 2.0f))
        tts.setSpeechRate(request.rate.coerceIn(0.5f, 2.0f))
        requestFocus()
        val id = "utt-${seq++}"
        currentId = id
        currentDone = request.onDone
        val result = tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            main.removeCallbacks(watchdog)
            currentDone = null
            currentId = null
            abandonFocus()
            request.onDone()
        } else {
            main.postDelayed(watchdog, watchdogMs(request.text))
        }
    }

    private fun watchdogMs(text: String): Long =
        (text.length * 300L + 7000L).coerceIn(10000L, 45000L)

    private fun finish(id: String?) {
        if (id == null || id != currentId) return
        main.removeCallbacks(watchdog)
        val callback = currentDone
        currentDone = null
        currentId = null
        abandonFocus()
        callback?.invoke()
        if (pending.isNotEmpty()) {
            play(pending.removeFirst())
        }
    }

    fun stop() {
        main.post {
            main.removeCallbacks(watchdog)
            currentDone = null
            currentId = null
            try {
                tts.stop()
            } catch (_: Exception) {
            }
            abandonFocus()
        }
    }

    fun shutdown() {
        main.post {
            main.removeCallbacks(watchdog)
            currentDone = null
            currentId = null
            pending.clear()
            try {
                tts.stop()
            } catch (_: Exception) {
            }
            tts.shutdown()
            abandonFocus()
        }
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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
