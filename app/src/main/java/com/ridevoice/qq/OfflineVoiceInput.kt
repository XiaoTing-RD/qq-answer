package com.ridevoice.qq

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

class OfflineVoiceInput(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val am = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var callback: ((VoiceInput.Result) -> Unit)? = null
    private var done = true
    private var heardSpeech = false
    private var generation = 0
    private var focusRequest: AudioFocusRequest? = null

    private val watchdog = Runnable {
        finish(
            null,
            if (heardSpeech) VoiceInput.ERROR_TIMEOUT else VoiceInput.ERROR_SILENCE
        )
    }

    fun listen(
        timeoutMs: Long,
        onStatus: (String) -> Unit = {},
        onResult: (VoiceInput.Result) -> Unit
    ) {
        main.post {
            cancelInternal()
            done = false
            heardSpeech = false
            callback = onResult
            val token = generation

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                finish(null, SpeechRecognizerCodes.PERMISSIONS)
                return@post
            }

            onStatus(
                if (VoskModelHolder.isReady) "正在听，请说…"
                else "正在准备离线识别模型…"
            )
            VoskModelHolder.ensure(context) { model, error ->
                if (done || token != generation) return@ensure
                if (model == null) {
                    Log.e("OfflineVoiceInput", "model unavailable: $error")
                    finish(null, VoiceInput.ERROR_OFFLINE_MODEL)
                    return@ensure
                }
                onStatus("正在听，请说…")
                start(model, timeoutMs, token)
            }
        }
    }

    private fun start(model: Model, timeoutMs: Long, token: Int) {
        if (done || token != generation) return
        requestFocus()

        val localRecognizer = try {
            Recognizer(model, 16000.0f)
        } catch (exception: Exception) {
            Log.e("OfflineVoiceInput", "recognizer failed", exception)
            finish(null, VoiceInput.ERROR_OFFLINE_INIT)
            return
        }
        recognizer = localRecognizer

        val service = try {
            SpeechService(localRecognizer, 16000.0f)
        } catch (exception: Exception) {
            Log.e("OfflineVoiceInput", "audio init failed", exception)
            finish(null, VoiceInput.ERROR_OFFLINE_AUDIO)
            return
        }
        speechService = service

        val listener = object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                val partial = parse(hypothesis, "partial")
                if (partial.isNotEmpty()) heardSpeech = true
            }

            override fun onResult(hypothesis: String?) {
                val text = parse(hypothesis, "text")
                if (text.isNotEmpty()) {
                    heardSpeech = true
                    main.post { finish(text, 0) }
                } else {
                    heardSpeech = true
                }
            }

            override fun onFinalResult(hypothesis: String?) {
                val text = parse(hypothesis, "text")
                main.post {
                    when {
                        text.isNotEmpty() -> finish(text, 0)
                        heardSpeech -> finish(null, VoiceInput.ERROR_EMPTY)
                        else -> finish(null, VoiceInput.ERROR_SILENCE)
                    }
                }
            }

            override fun onError(exception: Exception?) {
                Log.e("OfflineVoiceInput", "recognition error", exception)
                main.post { finish(null, VoiceInput.ERROR_OFFLINE_AUDIO) }
            }

            override fun onTimeout() {
                main.post {
                    finish(
                        null,
                        if (heardSpeech) VoiceInput.ERROR_TIMEOUT else VoiceInput.ERROR_SILENCE
                    )
                }
            }
        }

        val started = try {
            service.startListening(listener, timeoutMs.toInt())
        } catch (exception: Exception) {
            Log.e("OfflineVoiceInput", "start failed", exception)
            false
        }
        if (!started) {
            finish(null, VoiceInput.ERROR_OFFLINE_AUDIO)
            return
        }
        main.postDelayed(watchdog, timeoutMs + 3000L)
    }

    private fun parse(hypothesis: String?, key: String): String = runCatching {
        JSONObject(hypothesis ?: "").optString(key).replace(Regex("\\s+"), "").trim()
    }.getOrDefault("")

    private fun finish(text: String?, error: Int) {
        if (done) return
        done = true
        generation++
        main.removeCallbacks(watchdog)
        val result = callback
        callback = null
        stopEngine()
        abandonFocus()
        result?.invoke(VoiceInput.Result(text, error, "offline"))
    }

    private fun stopEngine() {
        val service = speechService
        speechService = null
        try {
            service?.cancel()
        } catch (_: Exception) {
        }
        try {
            service?.shutdown()
        } catch (_: Exception) {
        }
        val localRecognizer = recognizer
        recognizer = null
        try {
            localRecognizer?.close()
        } catch (_: Exception) {
        }
    }

    private fun cancelInternal() {
        done = true
        generation++
        main.removeCallbacks(watchdog)
        callback = null
        stopEngine()
        abandonFocus()
    }

    fun stop() {
        main.post { cancelInternal() }
    }

    fun destroy() {
        main.post { cancelInternal() }
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

    private object SpeechRecognizerCodes {
        const val PERMISSIONS = android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
    }
}
