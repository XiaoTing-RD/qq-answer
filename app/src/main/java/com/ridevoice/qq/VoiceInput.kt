package com.ridevoice.qq

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceInput(private val context: Context) {

    data class Result(
        val text: String?,
        val error: Int,
        val engine: String = "system"
    ) {
        val ok: Boolean
            get() = !text.isNullOrBlank()

        val silent: Boolean
            get() = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == ERROR_SILENCE

        val label: String
            get() = labelFor(error)
    }

    companion object {
        const val ERROR_UNAVAILABLE = -100
        const val ERROR_TIMEOUT = -101
        const val ERROR_START = -102
        const val ERROR_EMPTY = -103
        const val ERROR_SILENCE = -104
        const val ERROR_OFFLINE_MODEL = -110
        const val ERROR_OFFLINE_INIT = -111
        const val ERROR_OFFLINE_AUDIO = -112

        fun labelFor(error: Int): String = when (error) {
            0 -> "正常"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            SpeechRecognizer.ERROR_NETWORK -> "网络不可用"
            SpeechRecognizer.ERROR_AUDIO -> "录音失败，麦克风可能被占用"
            SpeechRecognizer.ERROR_SERVER -> "语音识别服务出错"
            SpeechRecognizer.ERROR_CLIENT -> "系统拦住了语音识别（常见于后台录音限制）"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到说话"
            SpeechRecognizer.ERROR_NO_MATCH -> "没听清"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别忙"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限被拒绝"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "语音请求太频繁"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "语音服务连接断开"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "语音服务不支持中文"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "中文语音包不可用"
            ERROR_UNAVAILABLE -> "手机没有可用的语音识别服务"
            ERROR_TIMEOUT -> "等待语音结果超时"
            ERROR_START -> "语音识别启动失败"
            ERROR_EMPTY -> "识别结果为空"
            ERROR_SILENCE -> "没有听到说话"
            ERROR_OFFLINE_MODEL -> "离线识别模型加载失败"
            ERROR_OFFLINE_INIT -> "离线识别初始化失败"
            ERROR_OFFLINE_AUDIO -> "离线识别录音失败，麦克风可能被占用"
            else -> "未知语音错误($error)"
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val am = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var recognizer: SpeechRecognizer? = null
    private var callback: ((Result) -> Unit)? = null
    private var finished = true
    private var heardSpeech = false
    private var focusRequest: AudioFocusRequest? = null

    private val timeout = Runnable {
        finish(null, if (heardSpeech) ERROR_TIMEOUT else ERROR_SILENCE)
    }

    fun listen(timeoutMs: Long, onResult: (Result) -> Unit) {
        main.post {
            cancelInternal()
            finished = false
            heardSpeech = false
            callback = onResult

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                finish(null, ERROR_UNAVAILABLE)
                return@post
            }

            requestFocus()
            val speech = try {
                SpeechRecognizer.createSpeechRecognizer(context)
            } catch (_: Exception) {
                finish(null, ERROR_START)
                return@post
            }
            recognizer = speech

            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                }

                override fun onBeginningOfSpeech() {
                    heardSpeech = true
                }

                override fun onRmsChanged(rmsdB: Float) {
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                }

                override fun onEndOfSpeech() {
                    heardSpeech = true
                }

                override fun onError(error: Int) {
                    finish(null, error)
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (text.isNullOrEmpty()) {
                        finish(null, ERROR_EMPTY)
                    } else {
                        finish(text, 0)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
            }

            try {
                speech.startListening(intent)
            } catch (_: Exception) {
                finish(null, ERROR_START)
                return@post
            }
            main.postDelayed(timeout, timeoutMs)
        }
    }

    private fun finish(text: String?, error: Int) {
        if (finished) return
        finished = true
        main.removeCallbacks(timeout)
        val result = callback
        callback = null
        destroyRecognizer()
        abandonFocus()
        result?.invoke(Result(text?.trim()?.takeIf { it.isNotEmpty() }, error))
    }

    private fun destroyRecognizer() {
        val speech = recognizer ?: return
        recognizer = null
        try {
            speech.cancel()
            speech.destroy()
        } catch (_: Exception) {
        }
    }

    private fun cancelInternal() {
        finished = true
        main.removeCallbacks(timeout)
        callback = null
        destroyRecognizer()
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
}
