package com.ridevoice.qq

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.android.StorageService

object VoskModelHolder {

    private const val ASSET_DIR = "vosk-model-cn"
    private const val TARGET_DIR = "vosk-model-cn"

    private val lock = Any()
    private val waiters = ArrayList<(Model?, String?) -> Unit>()
    private var model: Model? = null
    private var loading = false
    private var lastError: String? = null

    val isReady: Boolean
        get() = synchronized(lock) { model != null }

    val isLoading: Boolean
        get() = synchronized(lock) { loading }

    val error: String?
        get() = synchronized(lock) { lastError }

    fun ensure(context: Context, callback: (Model?, String?) -> Unit) {
        synchronized(lock) {
            model?.let {
                callback(it, null)
                return
            }
            waiters.add(callback)
            if (loading) return
            loading = true
            lastError = null
        }

        try {
            StorageService.unpack(
                context.applicationContext,
                ASSET_DIR,
                TARGET_DIR,
                { loaded ->
                    deliver(loaded, null)
                },
                { exception ->
                    deliver(null, exception.message ?: "离线模型解包失败")
                }
            )
        } catch (exception: Exception) {
            Log.e("VoskModelHolder", "unpack failed", exception)
            deliver(null, exception.message ?: "离线模型解包失败")
        }
    }

    fun preload(context: Context) {
        ensure(context) { _, error ->
            if (error != null) {
                Log.w("VoskModelHolder", "preload failed: $error")
            }
        }
    }

    private fun deliver(loaded: Model?, message: String?) {
        val pending: List<(Model?, String?) -> Unit>
        synchronized(lock) {
            if (loaded != null) {
                model = loaded
                lastError = null
            } else {
                lastError = message
            }
            loading = false
            pending = waiters.toList()
            waiters.clear()
        }
        pending.forEach { callback ->
            runCatching { callback(loaded, message) }
        }
    }
}
