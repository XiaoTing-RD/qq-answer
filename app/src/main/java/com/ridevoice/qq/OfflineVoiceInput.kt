package com.ridevoice.qq

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.abs

class OfflineVoiceInput(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val am = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var callback: ((VoiceInput.Result) -> Unit)? = null
    private var audioRecord: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var recordThread: Thread? = null
    @Volatile
    private var cancelFlag = false
    private var done = true
    private var generation = 0
    private var focusRequest: AudioFocusRequest? = null

    private val watchdog = Runnable {
        finish(null, if (heardSpeech) VoiceInput.ERROR_TIMEOUT else VoiceInput.ERROR_SILENCE)
    }

    @Volatile
    private var heardSpeech = false

    fun listen(
        timeoutMs: Long,
        confirmMode: Boolean = false,
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
                finish(null, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
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
                start(model, timeoutMs, confirmMode, token)
            }
        }
    }

    private fun start(model: Model, timeoutMs: Long, confirmMode: Boolean, token: Int) {
        if (done || token != generation) return
        requestFocus()

        val recorder = createAudioRecord()
        if (recorder == null) {
            finish(null, VoiceInput.ERROR_OFFLINE_AUDIO)
            return
        }
        audioRecord = recorder

        val localRecognizer = try {
            if (confirmMode) {
                val grammar = """
                    ["确认","确定","发送","取消","不对","等一下","[unk]"]
                """.trimIndent()
                Recognizer(model, 16000.0f, grammar)
            } else {
                Recognizer(model, 16000.0f)
            }
        } catch (exception: Exception) {
            Log.e("OfflineVoiceInput", "recognizer failed", exception)
            recorder.release()
            audioRecord = null
            finish(null, VoiceInput.ERROR_OFFLINE_INIT)
            return
        }
        recognizer = localRecognizer

        cancelFlag = false
        recordThread = Thread({
            recordLoop(recorder, localRecognizer, timeoutMs, token)
        }, "vosk-record").apply {
            start()
        }
        main.postDelayed(watchdog, timeoutMs + 3000L)
    }

    private fun recordLoop(
        recorder: AudioRecord,
        localRecognizer: Recognizer,
        timeoutMs: Long,
        token: Int
    ) {
        val buffer = ShortArray(3200)
        var gain = 1.0f
        var speechSeen = false
        var lastSpeechAt = 0L
        val startedAt = SystemClock.uptimeMillis()

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                deliver(token, null, VoiceInput.ERROR_OFFLINE_AUDIO)
                return
            }

            while (!cancelFlag) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    deliver(token, null, VoiceInput.ERROR_OFFLINE_AUDIO)
                    return
                }

                var peak = 0
                for (i in 0 until read) {
                    val value = abs(buffer[i].toInt())
                    if (value > peak) peak = value
                }
                if (peak >= 200) {
                    val target = 13000f
                    val desired = (target / peak).coerceIn(1.0f, 5.0f)
                    gain = gain * 0.8f + desired * 0.2f
                    val applied = gain.coerceIn(1.0f, 5.0f)
                    for (i in 0 until read) {
                        val value = (buffer[i] * applied).toInt().coerceIn(-32768, 32767)
                        buffer[i] = value.toShort()
                    }
                    peak = (peak * applied).toInt().coerceAtMost(32767)
                }

                val now = SystemClock.uptimeMillis()
                if (peak >= 800) {
                    speechSeen = true
                    heardSpeech = true
                    lastSpeechAt = now
                }

                if (localRecognizer.acceptWaveForm(buffer, read)) {
                    val text = parse(localRecognizer.result, "text")
                    if (text.isNotEmpty()) {
                        deliver(token, text, 0)
                        return
                    }
                } else {
                    val partial = parse(localRecognizer.partialResult, "partial")
                    if (partial.isNotEmpty()) {
                        speechSeen = true
                        heardSpeech = true
                        lastSpeechAt = now
                    }
                }

                if (speechSeen && now - lastSpeechAt >= 1800L) {
                    val text = parse(localRecognizer.finalResult, "text")
                    deliver(token, text.takeIf { it.isNotEmpty() }, if (text.isEmpty()) VoiceInput.ERROR_EMPTY else 0)
                    return
                }

                if (now - startedAt >= timeoutMs) {
                    val text = parse(localRecognizer.finalResult, "text")
                    if (text.isNotEmpty()) {
                        deliver(token, text, 0)
                    } else {
                        deliver(
                            token,
                            null,
                            if (speechSeen) VoiceInput.ERROR_TIMEOUT
                            else VoiceInput.ERROR_SILENCE
                        )
                    }
                    return
                }
            }
        } catch (exception: Exception) {
            Log.e("OfflineVoiceInput", "record failed", exception)
            deliver(token, null, VoiceInput.ERROR_OFFLINE_AUDIO)
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            try {
                localRecognizer.close()
            } catch (_: Exception) {
            }
            synchronized(this) {
                if (audioRecord === recorder) audioRecord = null
                if (recognizer === localRecognizer) recognizer = null
                if (recordThread === Thread.currentThread()) recordThread = null
            }
        }
    }

    private fun deliver(token: Int, text: String?, error: Int) {
        main.post {
            if (done || token != generation) return@post
            finish(text, error)
        }
    }

    private fun finish(text: String?, error: Int) {
        if (done) return
        done = true
        generation++
        cancelFlag = true
        main.removeCallbacks(watchdog)
        val result = callback
        callback = null
        stopRecording()
        abandonFocus()
        if (text != null && text.isNotEmpty()) heardSpeech = true
        result?.invoke(VoiceInput.Result(text, error, "offline"))
    }

    private fun stopRecording() {
        val thread = recordThread
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(300L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun cancelInternal() {
        done = true
        generation++
        cancelFlag = true
        main.removeCallbacks(watchdog)
        callback = null
        stopRecording()
        abandonFocus()
    }

    fun stop() {
        main.post { cancelInternal() }
    }

    fun destroy() {
        main.post { cancelInternal() }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val bufferSize = maxOf(minBuffer, 12800)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )
        for (source in sources) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull() ?: continue
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                return recorder
            }
            recorder.release()
        }
        return null
    }

    private fun parse(hypothesis: String?, key: String): String = runCatching {
        JSONObject(hypothesis ?: "").optString(key).replace(Regex("\\s+"), "").trim()
    }.getOrDefault("")

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
