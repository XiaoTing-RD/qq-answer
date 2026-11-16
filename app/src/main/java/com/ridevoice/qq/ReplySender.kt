package com.ridevoice.qq

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification

object ReplySender {

    data class SendResult(
        val success: Boolean,
        val reason: String = ""
    )

    fun send(context: Context, original: StatusBarNotification?, text: String): SendResult {
        if (original == null) {
            return SendResult(false, "没有保存到 QQ 通知")
        }

        val originalTitle = original.notification.extras
            ?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
        val active = runCatching {
            QQListener.instance?.activeNotifications?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val candidates = ArrayList<StatusBarNotification>()
        candidates.add(original)
        active.firstOrNull { it.key == original.key }?.let { candidates.add(it) }
        if (!originalTitle.isNullOrEmpty()) {
            val normalizedTitle = normalizeTitle(originalTitle)
            active.filter {
                QQListener.isQq(it.packageName) &&
                    it.notification.extras
                        ?.getCharSequence(Notification.EXTRA_TITLE)
                        ?.toString()
                        ?.let { title -> normalizeTitle(title) == normalizedTitle } == true &&
                    hasReplyAction(it)
            }.forEach { candidate ->
                if (candidates.none { it.key == candidate.key }) candidates.add(candidate)
            }
        }

        var sawReplyAction = false
        var sawAnyNotification = false
        var lastException: String? = null

        for (candidate in candidates) {
            sawAnyNotification = true
            if (sendTo(context, candidate, text)) {
                return SendResult(true)
            }
            if (hasReplyAction(candidate)) sawReplyAction = true
            lastException = lastException ?: lastPendingIntentFailure
        }

        val reason = when {
            !sawAnyNotification -> "原通知已经不在通知栏里了"
            !sawReplyAction -> "QQ 通知没有直接回复按钮"
            lastException != null -> "QQ 直接回复失败：$lastException"
            else -> "QQ 没有接受这次直接回复"
        }
        return SendResult(false, reason)
    }

    private var lastPendingIntentFailure: String? = null

    private fun normalizeTitle(value: String): String =
        value.replace(Regex("[（(][^）)]{0,12}[)）]$"), "")
            .replace(Regex("\\s+"), "")
            .lowercase()

    private fun hasReplyAction(sbn: StatusBarNotification): Boolean =
        sbn.notification.actions?.any { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } == true

    private fun sendTo(context: Context, sbn: StatusBarNotification, text: String): Boolean {
        val actions = sbn.notification.actions ?: return false
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            val freeForms = inputs.filter { it.allowFreeFormInput }
            if (freeForms.isEmpty()) continue

            val intent = Intent()
            val results = Bundle()
            freeForms.forEach { input ->
                results.putCharSequence(input.resultKey, text)
            }
            RemoteInput.addResultsToIntent(freeForms.toTypedArray(), intent, results)
            intent.putExtra(Intent.EXTRA_TEXT, text)
            runCatching {
                intent.clipData = ClipData.newPlainText("reply", text)
            }
            try {
                action.actionIntent.send(context, 0, intent)
                lastPendingIntentFailure = null
                return true
            } catch (exception: PendingIntent.CanceledException) {
                lastPendingIntentFailure = "通知动作已失效"
            } catch (exception: Exception) {
                lastPendingIntentFailure = exception.message ?: "发送异常"
            }
        }
        return false
    }
}
