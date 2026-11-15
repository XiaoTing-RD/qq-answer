package com.ridevoice.qq

import android.content.Context
import android.speech.SpeechRecognizer

class SpeechRouter(
    private val context: Context,
    private val prefs: Prefs
) {

    companion object {
        @Volatile
        var systemBlocked: Boolean = false
            private set

        fun resetSystemBlock() {
            systemBlocked = false
        }
    }

    private val system = VoiceInput(context)
    private val offline = OfflineVoiceInput(context)

    fun preload() {
        if (prefs.voiceEngine != Prefs.ENGINE_SYSTEM) {
            VoskModelHolder.preload(context)
        }
    }

    fun listen(
        timeoutMs: Long,
        onStatus: (String) -> Unit = {},
        onResult: (VoiceInput.Result) -> Unit
    ) {
        val engine = prefs.voiceEngine
        if (engine == Prefs.ENGINE_OFFLINE ||
            (engine == Prefs.ENGINE_AUTO && systemBlocked)
        ) {
            offline.listen(timeoutMs, onStatus, onResult)
            return
        }

        system.listen(timeoutMs) { result ->
            if (engine == Prefs.ENGINE_AUTO && shouldFallback(result)) {
                systemBlocked = true
                onStatus("系统语音被拦，切换离线识别…")
                offline.listen(timeoutMs, onStatus, onResult)
            } else {
                if (result.ok) systemBlocked = false
                onResult(result)
            }
        }
    }

    fun stop() {
        system.stop()
        offline.stop()
    }

    fun destroy() {
        system.destroy()
        offline.destroy()
    }

    private fun shouldFallback(result: VoiceInput.Result): Boolean = when (result.error) {
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        VoiceInput.ERROR_UNAVAILABLE,
        VoiceInput.ERROR_START -> true

        else -> false
    }
}
