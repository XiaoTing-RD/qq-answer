package com.ridevoice.qq

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class QQListener : NotificationListenerService() {

    companion object {
        const val QQ_PACKAGE = "com.tencent.mobileqq"
        const val QQ_LITE_PACKAGE = "com.tencent.qqlite"

        fun isQq(packageName: String?): Boolean =
            packageName == QQ_PACKAGE || packageName == QQ_LITE_PACKAGE

        @Volatile
        var instance: QQListener? = null
            private set
    }

    private val seen = HashMap<String, Pair<String, Long>>()
    private var connectedAt = 0L
    private val prefs by lazy { Prefs(this) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        connectedAt = System.currentTimeMillis()
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val status = sbn ?: return
        if (!isQq(status.packageName)) return
        val service = RideService.instance ?: return
        val notification = status.notification ?: return

        if (status.postTime < connectedAt - 3000) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        }
        if (title.isEmpty() || text.isEmpty()) return
        if (title == "QQ空间" || title == "QQ团队") return
        if (text.endsWith("条新消息") && text.length <= 12) return
        if (RideService.isEcho(text)) return

        var group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        var sender = title
        val split = text.indexOfFirst { it == ':' || it == '：' }
        if ((group || title == "QQ") && split in 1..24 && split < text.length - 1) {
            val name = text.substring(0, split).trim()
            if (name.isNotEmpty() && !name.startsWith("http")) {
                sender = if (title == "QQ") name else "$title，$name"
                text = text.substring(split + 1).trim()
                if (title == "QQ") group = false
            }
        }
        if (title == "QQ" && sender == title) return
        if (!isWantedContact(sender, group)) return

        val fingerprint = sender + "\u0001" + text
        val now = System.currentTimeMillis()
        val previous = seen[status.key]
        if (previous != null && previous.first == fingerprint && now - previous.second < 4000) return
        seen[status.key] = fingerprint to now
        if (seen.size > 40) {
            seen.entries.removeAll { now - it.value.second > 120_000 }
        }

        service.onQQMessage(sender, text, group, status)
    }

    private fun isWantedContact(sender: String, group: Boolean): Boolean {
        if (!prefs.onlyContact) return true
        val wanted = compact(prefs.contactName)
        if (wanted.isEmpty()) return true
        val raw = (if (group) sender.substringAfterLast("，") else sender).trim()
        val cleaned = raw.replace(Regex("[（(][^）)]{0,12}[)）]$"), "").trim()
        return compact(raw) == wanted || compact(cleaned) == wanted
    }

    private fun compact(value: String): String =
        value.replace(Regex("\\s+"), "").lowercase()

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
