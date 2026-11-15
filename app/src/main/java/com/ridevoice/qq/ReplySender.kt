package com.ridevoice.qq

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification

object ReplySender {

    fun send(context: Context, original: StatusBarNotification?, text: String): Boolean {
        if (original == null) return false

        if (sendTo(context, original, text)) return true

        val active = runCatching {
            QQListener.instance?.activeNotifications?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val originalTitle = original.notification.extras
            ?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()

        val candidates = ArrayList<StatusBarNotification>()
        active.firstOrNull { it.key == original.key }?.let { candidates.add(it) }
        if (!originalTitle.isNullOrEmpty()) {
            active.filter {
                QQListener.isQq(it.packageName) &&
                    it.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == originalTitle &&
                    hasReplyAction(it)
            }.forEach { candidate ->
                if (candidates.none { it.key == candidate.key }) candidates.add(candidate)
            }
        }

        for (candidate in candidates) {
            if (sendTo(context, candidate, text)) return true
        }
        return false
    }

    private fun hasReplyAction(sbn: StatusBarNotification): Boolean =
        sbn.notification.actions?.any { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } == true

    private fun sendTo(context: Context, sbn: StatusBarNotification, text: String): Boolean {
        val actions = sbn.notification.actions ?: return false
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            val freeForm = inputs.firstOrNull { it.allowFreeFormInput } ?: continue
            val intent = Intent()
            val data = Bundle()
            data.putCharSequence(freeForm.resultKey, text)
            RemoteInput.addResultsToIntent(arrayOf(freeForm), intent, data)
            try {
                action.actionIntent.send(context, 0, intent)
                return true
            } catch (_: PendingIntent.CanceledException) {
            } catch (_: Exception) {
            }
        }
        return false
    }
}
