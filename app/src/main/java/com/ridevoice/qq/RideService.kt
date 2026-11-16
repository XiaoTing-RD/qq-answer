package com.ridevoice.qq

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.notification.StatusBarNotification
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class RideService : Service() {

    companion object {
        @Volatile
        var instance: RideService? = null
            private set

        const val ACTION_START = "com.ridevoice.qq.action.START"
        const val ACTION_STOP = "com.ridevoice.qq.action.STOP"
        const val ACTION_REPEAT = "com.ridevoice.qq.action.REPEAT"
        const val ACTION_CANCEL = "com.ridevoice.qq.action.CANCEL"
        const val ACTION_OPEN_QQ = "com.ridevoice.qq.action.OPEN_QQ"
        const val ACTION_RETRY_SEND = "com.ridevoice.qq.action.RETRY_SEND"

        private const val CHANNEL_STATUS = "ride_status"
        private const val CHANNEL_ALERT = "ride_alert"
        private const val NOTIF_ID = 1001
        private const val ALERT_NOTIF_ID = 1002

        @Volatile
        private var lastSentText = ""

        @Volatile
        private var lastSentAt = 0L

        fun isEcho(text: String): Boolean {
            val t = text.trim()
            return t.isNotEmpty() && t == lastSentText && System.currentTimeMillis() - lastSentAt < 8000
        }

        private fun noteSent(text: String) {
            lastSentText = text
            lastSentAt = System.currentTimeMillis()
        }

        private val REPLY_PREFIXES = listOf(
            "回复他", "回复她", "回复一下", "回复说", "回复",
            "回他", "回她", "回一下", "你就说", "你就回"
        )

        private val NEGATIVE_WORDS = listOf(
            "不回", "不用回", "不要回", "不需要回", "别回", "不用了", "不用",
            "算了", "取消", "跳过", "免了", "不管", "不听", "下一个",
            "别发", "不要发", "不发", "先别", "先不发", "等等", "等一下", "等会儿",
            "等会", "稍等", "重说", "再说一遍", "改一下", "不对", "错了", "有误",
            "不好", "不行", "不要", "不需要", "不想", "不可以", "不确定", "不确认", "别急"
        )

        private val CONFIRM_WORDS = listOf(
            "确认", "确定", "发送", "发吧", "发出去", "可以发", "就这样", "没问题", "ok"
        )
    }

    private data class Incoming(
        val sender: String,
        val text: String,
        val group: Boolean,
        val sbn: StatusBarNotification?
    )

    private enum class Stage {
        IDLE, ALERT, SPEAKING, LISTEN_DECISION, LISTEN_REPLY, LISTEN_CONFIRM, BUSY
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var speech: SpeechRouter
    private lateinit var musicAlarm: MusicAlarm
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val queue = ArrayDeque<Incoming>()
    private var stage = Stage.IDLE
    private var current: Incoming? = null
    private var draft = ""
    private var contentTries = 0
    private var confirmTries = 0
    private var failedText = ""
    private var failedSbn: StatusBarNotification? = null
    private var listenToken = 0
    private var replyAttempts = 0
    private var decisionTries = 0
    private var burstReceived = 0
    private var currentPosition = 0
    private var currentTotal = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        QQListener.requestRebind(this)
        createChannels()
        speaker = Speaker(this)
        speech = SpeechRouter(this, prefs)
        speech.preload()
        musicAlarm = MusicAlarm(this)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QQRideVoice:flow")
            ?.apply { setReferenceCounted(false) }
        startForegroundSafely("已开启，等待 QQ 消息")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.rideEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REPEAT -> repeatCurrent()
            ACTION_CANCEL -> cancelByUser()
            ACTION_OPEN_QQ -> openQq()
            ACTION_RETRY_SEND -> retryFailedSend()
            ACTION_START -> {
                QQListener.requestRebind(this)
                if (stage == Stage.IDLE) updateNotification("已开启，等待 QQ 消息")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        stage = Stage.IDLE
        current = null
        queue.clear()
        musicAlarm.stop()
        speaker.shutdown()
        speech.destroy()
        tone?.release()
        tone = null
        releaseWake()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    fun onQQMessage(sender: String, text: String, group: Boolean, sbn: StatusBarNotification?) {
        main.post {
            if (group && !prefs.readGroups) return@post
            if (queue.size >= 100) return@post
            val cleanText = text.replace(Regex("\\s+"), " ").trim().take(400)
            val cleanSender = sender.replace(Regex("\\s+"), " ").trim().take(40)
            if (cleanText.isEmpty() || cleanSender.isEmpty()) return@post
            if (isEcho(cleanText)) return@post

            if (queue.isEmpty() && current == null) {
                burstReceived = 0
            }
            burstReceived++
            queue.addLast(Incoming(cleanSender, cleanText, group, sbn))
            if (stage == Stage.IDLE) {
                updateNotification("收到 ${queue.size} 条，准备朗读")
                main.postDelayed({ if (stage == Stage.IDLE) playNext() }, 900)
            } else {
                updateNotification(currentStatusText())
            }
        }
    }

    private fun playNext() {
        val message = queue.removeFirstOrNull()
        if (message == null) {
            stage = Stage.IDLE
            current = null
            burstReceived = 0
            currentPosition = 0
            currentTotal = 0
            releaseWake()
            updateNotification("已开启，等待 QQ 消息")
            return
        }
        current = message
        currentTotal = burstReceived.coerceAtLeast(queue.size + 1)
        currentPosition = (currentTotal - queue.size).coerceAtLeast(1)
        draft = ""
        contentTries = 0
        confirmTries = 0
        replyAttempts = 0
        decisionTries = 0
        listenToken++
        wake()
        if (prefs.musicEnabled &&
            prefs.musicUri.isNotBlank() &&
            currentPosition == 1
        ) {
            stage = Stage.ALERT
            updateNotification("第 $currentPosition/$currentTotal 条：提醒音乐")
            musicAlarm.play(prefs.musicUri, 6000L) {
                if (stage == Stage.ALERT) startMessageSpeech(message)
            }
        } else {
            startMessageSpeech(message)
        }
    }

    private fun startMessageSpeech(message: Incoming) {
        stage = Stage.SPEAKING
        updateNotification("第 $currentPosition/$currentTotal 条：朗读中")
        speakAndThen(readText(message)) {
            if (stage == Stage.SPEAKING) startDecisionListening()
        }
    }

    private fun readText(message: Incoming): String {
        val body = if (message.text.length > 160) {
            message.text.take(160) + "，后面省略"
        } else {
            message.text
        }
        val prefix = if (currentTotal > 1) {
            "第 $currentPosition 条，共 $currentTotal 条。"
        } else {
            ""
        }
        return "$prefix${message.sender}说：$body。要回吗？"
    }

    private fun startDecisionListening(delayMs: Long = 320L) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failCurrent("麦克风权限被系统收回了，请打开应用重新授权")
            return
        }
        stage = Stage.LISTEN_DECISION
        updateNotification("第 $currentPosition/$currentTotal 条：要回吗？")
        val token = ++listenToken
        main.postDelayed({
            if (stage == Stage.LISTEN_DECISION && token == listenToken) {
                beep()
                main.postDelayed({
                    if (stage == Stage.LISTEN_DECISION && token == listenToken) {
                        speech.listen(
                            4000L,
                            onStatus = { status ->
                                if (stage == Stage.LISTEN_DECISION) updateNotification(status)
                            }
                        ) { onDecisionHeard(it) }
                    }
                }, 160L)
            }
        }, delayMs)
    }

    private fun startContentListening(delayMs: Long = 320L) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failCurrent("麦克风权限被系统收回了，请打开应用重新授权")
            return
        }
        stage = Stage.LISTEN_REPLY
        updateNotification("正在听内容，请说…")
        val token = ++listenToken
        main.postDelayed({
            if (stage == Stage.LISTEN_REPLY && token == listenToken) {
                beep()
                main.postDelayed({
                    if (stage == Stage.LISTEN_REPLY && token == listenToken) {
                        speech.listen(
                            12000L,
                            onStatus = { status ->
                                if (stage == Stage.LISTEN_REPLY) updateNotification(status)
                            }
                        ) { onReplyHeard(it) }
                    }
                }, 160L)
            }
        }, delayMs)
    }

    private fun onDecisionHeard(result: VoiceInput.Result) {
        if (stage != Stage.LISTEN_DECISION) return
        val raw = result.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (raw.isEmpty()) {
            handleDecisionError(result)
            return
        }

        prefs.lastVoiceError = ""
        val normalized = normalize(raw)
        if (normalized.isEmpty()) {
            handleDecisionError(VoiceInput.Result(null, VoiceInput.ERROR_EMPTY))
            return
        }
        if (isNegative(normalized)) {
            finishCurrent("好，不回")
            return
        }

        val content = stripReplyCommand(normalized)
        if (content.isEmpty() || isDecisionAffirm(normalized)) {
            askForContent()
            return
        }

        draft = content.take(300)
        askConfirm()
    }

    private fun askForContent() {
        contentTries = 0
        stage = Stage.SPEAKING
        updateNotification("请说内容")
        speakAndThen("请说内容") {
            if (current != null) startContentListening()
        }
    }

    private fun isDecisionAffirm(value: String): Boolean {
        val trimmed = value.trim().trimEnd('的', '吧', '啊', '呀', '呢', '了')
        return trimmed in setOf(
            "回", "回复", "要回", "回吧", "要", "嗯", "好", "好的",
            "行", "可以", "对", "是的", "说吧", "说", "ok"
        )
    }

    private fun onReplyHeard(result: VoiceInput.Result) {
        if (stage != Stage.LISTEN_REPLY) return
        val raw = result.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (raw.isEmpty()) {
            handleReplyError(result)
            return
        }

        prefs.lastVoiceError = ""
        replyAttempts = 0
        val normalized = normalize(raw)
        if (normalized.isEmpty()) {
            handleReplyError(VoiceInput.Result(null, VoiceInput.ERROR_EMPTY))
            return
        }

        val hadCommand = REPLY_PREFIXES.any { normalized.startsWith(it) }
        val content = stripReplyCommand(normalized)

        if (content.isEmpty()) {
            if (!hadCommand && isNegative(normalized)) {
                finishCurrent("好，不回")
                return
            }
            contentTries++
            if (contentTries >= 2) {
                finishCurrent("没听到内容，已跳过")
                return
            }
            stage = Stage.SPEAKING
            speakAndThen("请说内容") {
                if (current != null) startContentListening()
            }
            return
        }

        if (!hadCommand && isNegative(normalized)) {
            finishCurrent("好，不回")
            return
        }

        draft = content.take(300)
        askConfirm()
    }

    private fun handleReplyError(result: VoiceInput.Result) {
        prefs.lastVoiceError = result.label
        replyAttempts++
        when {
            result.silent -> finishCurrent(null)
            result.error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                failCurrent("麦克风权限被拒绝了，请打开应用重新授权")

            result.error == VoiceInput.ERROR_UNAVAILABLE ->
                failCurrent("手机没有可用的语音识别服务，请打开应用看语音自检")

            result.error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                result.error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                failCurrent("语音识别服务不支持中文，请更换语音服务")

            result.error == VoiceInput.ERROR_OFFLINE_MODEL ||
                result.error == VoiceInput.ERROR_OFFLINE_INIT ->
                failCurrent("离线识别不可用：${result.label}，请打开应用自检")

            replyAttempts >= 2 ->
                failCurrent("语音识别失败：${result.label}，请打开应用看语音自检")

            else -> {
                val prompt = when (result.error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    VoiceInput.ERROR_EMPTY,
                    VoiceInput.ERROR_TIMEOUT -> "没听清，再说一次"

                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "语音服务连不上，再试一次"

                    SpeechRecognizer.ERROR_AUDIO,
                    VoiceInput.ERROR_OFFLINE_AUDIO -> "录音出错了，再试一次"

                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "稍等，再试一次"
                    SpeechRecognizer.ERROR_CLIENT -> "系统刚才拦住了语音，再试一次"
                    else -> "语音识别出错了，再试一次"
                }
                stage = Stage.SPEAKING
                updateNotification("${result.label}，重试中…")
                speakAndThen(prompt) {
                    if (current != null) startContentListening()
                }
            }
        }
    }

    private fun handleDecisionError(result: VoiceInput.Result) {
        prefs.lastVoiceError = result.label
        decisionTries++
        when {
            result.silent -> finishCurrent(null)
            result.error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                failCurrent("麦克风权限被拒绝了，请打开应用重新授权")

            result.error == VoiceInput.ERROR_UNAVAILABLE ->
                failCurrent("手机没有可用的语音识别服务，请打开应用看语音自检")

            result.error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                result.error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                failCurrent("语音识别服务不支持中文，请更换语音服务")

            result.error == VoiceInput.ERROR_OFFLINE_MODEL ||
                result.error == VoiceInput.ERROR_OFFLINE_INIT ->
                failCurrent("离线识别不可用：${result.label}，请打开应用自检")

            decisionTries >= 2 -> finishCurrent("没听清，已跳过")

            else -> {
                stage = Stage.SPEAKING
                updateNotification("没听清，要回吗？")
                speakAndThen("没听清，要回吗？") {
                    if (current != null) startDecisionListening(280L)
                }
            }
        }
    }

    private fun failCurrent(message: String) {
        updateNotification("$message；点开应用自检")
        finishCurrent(message)
    }

    private fun askConfirm() {
        val message = current ?: return
        listenToken++
        stage = Stage.LISTEN_CONFIRM
        confirmTries = 0
        updateNotification("待确认：$draft")
        speakAndThen("发给${message.sender}：$draft。确认发送吗？") {
            if (stage == Stage.LISTEN_CONFIRM) beginConfirmListening()
        }
    }

    private fun beginConfirmListening(delayMs: Long = 320L) {
        stage = Stage.LISTEN_CONFIRM
        updateNotification("听确认：$draft")
        val token = ++listenToken
        main.postDelayed({
            if (stage == Stage.LISTEN_CONFIRM && token == listenToken) {
                beep()
                main.postDelayed({
                    if (stage == Stage.LISTEN_CONFIRM && token == listenToken) {
                        speech.listen(
                            12000L,
                            confirmMode = true,
                            onStatus = { status ->
                                if (stage == Stage.LISTEN_CONFIRM) updateNotification(status)
                            }
                        ) { onConfirmHeard(it) }
                    }
                }, 160L)
            }
        }, delayMs)
    }

    private fun onConfirmHeard(result: VoiceInput.Result) {
        if (stage != Stage.LISTEN_CONFIRM) return
        val text = result.text?.trim().orEmpty()
        if (text.isEmpty()) {
            handleConfirmError(result)
            return
        }

        prefs.lastVoiceError = ""
        val normalized = normalize(text)
        if (normalized.isEmpty()) {
            retryConfirm()
            return
        }
        if (isNegative(normalized)) {
            draft = ""
            finishCurrent("已取消")
            return
        }
        if (isConfirm(normalized)) {
            sendDraft()
            return
        }
        retryConfirm()
    }

    private fun handleConfirmError(result: VoiceInput.Result) {
        prefs.lastVoiceError = result.label
        when {
            result.silent -> retryConfirm()
            result.error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                draft = ""
                failCurrent("麦克风权限被拒绝了，已取消发送")
            }

            result.error == VoiceInput.ERROR_UNAVAILABLE -> {
                draft = ""
                failCurrent("语音识别服务不可用，已取消发送")
            }

            result.error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                result.error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                draft = ""
                failCurrent("语音识别服务不支持中文，已取消发送")
            }

            result.error == VoiceInput.ERROR_OFFLINE_MODEL ||
                result.error == VoiceInput.ERROR_OFFLINE_INIT -> {
                draft = ""
                failCurrent("离线识别不可用，已取消发送")
            }

            else -> retryConfirm()
        }
    }

    private fun retryConfirm() {
        confirmTries++
        if (confirmTries >= 2) {
            draft = ""
            finishCurrent("没听清，已取消")
            return
        }
        stage = Stage.LISTEN_CONFIRM
        updateNotification("请说确认，或者取消")
        speakAndThen("请说确认，或者取消") {
            if (stage == Stage.LISTEN_CONFIRM) beginConfirmListening()
        }
    }

    private fun sendDraft() {
        val message = current ?: return
        val text = draft.trim()
        if (text.isEmpty()) {
            finishCurrent("已取消")
            return
        }
        stage = Stage.BUSY
        updateNotification("发送中…")
        val result = ReplySender.send(this, message.sbn, text)
        if (result.success) {
            prefs.lastSendError = ""
            noteSent(text)
            draft = ""
            finishCurrent("已发送")
        } else {
            failedText = text
            failedSbn = message.sbn
            prefs.lastSendError = result.reason
            val clipboard = getSystemService(ClipboardManager::class.java)
            runCatching {
                clipboard?.setPrimaryClip(ClipData.newPlainText("QQ回复", text))
            }
            notifyFailure("发送失败：${result.reason}。内容已保留：$text", text)
            draft = ""
            finishCurrent("发送失败：${result.reason}。内容已保留，可在通知栏重试，或打开QQ粘贴")
        }
    }

    private fun retryFailedSend() {
        val text = failedText
        if (text.isEmpty()) return
        val result = ReplySender.send(this, failedSbn, text)
        if (result.success) {
            prefs.lastSendError = ""
            noteSent(text)
            failedText = ""
            failedSbn = null
            runCatching {
                getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIF_ID)
            }
            if (stage == Stage.IDLE) {
                updateNotification("已开启，等待 QQ 消息")
                speakAndThen("已发送") { }
            }
        } else {
            prefs.lastSendError = result.reason
            notifyFailure("还是没发出去：${result.reason}。内容已保留：$text", text)
            if (stage == Stage.IDLE) {
                speakAndThen("还是没发出去，${result.reason}，内容已保留") { }
            }
        }
    }

    private fun finishCurrent(say: String?) {
        listenToken++
        musicAlarm.stop()
        speech.stop()
        releaseWake()
        current = null
        draft = ""
        if (say.isNullOrEmpty()) {
            stage = Stage.IDLE
            main.postDelayed({ playNext() }, 400)
        } else {
            stage = Stage.BUSY
            speakAndThen(say) {
                stage = Stage.IDLE
                main.postDelayed({ playNext() }, 300)
            }
        }
    }

    private fun repeatCurrent() {
        val message = current ?: return
        listenToken++
        replyAttempts = 0
        confirmTries = 0
        contentTries = 0
        decisionTries = 0
        musicAlarm.stop()
        speaker.stop()
        speech.stop()
        stage = Stage.SPEAKING
        updateNotification("第 $currentPosition/$currentTotal 条：重听")
        speakAndThen(readText(message)) {
            if (stage == Stage.SPEAKING) startDecisionListening()
        }
    }

    private fun cancelByUser() {
        listenToken++
        musicAlarm.stop()
        speaker.stop()
        speech.stop()
        releaseWake()
        current = null
        draft = ""
        stage = Stage.IDLE
        updateNotification("已取消，等待消息")
        main.postDelayed({ playNext() }, 600)
    }

    private fun speakAndThen(text: String, then: () -> Unit) {
        val preset = prefs.preset
        speaker.speak(text, preset.pitch, preset.rate) {
            main.post(then)
        }
    }

    private fun beep() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120) }
    }

    private fun wake() {
        if (wakeLock?.isHeld != true) {
            runCatching { wakeLock?.acquire(180_000L) }
        }
    }

    private fun releaseWake() {
        if (wakeLock?.isHeld == true) {
            runCatching { wakeLock?.release() }
        }
    }

    private fun currentStatusText(): String {
        val message = current
        return when (stage) {
            Stage.IDLE -> "已开启，等待 QQ 消息"
            Stage.ALERT -> "第 $currentPosition/$currentTotal 条：提醒音乐"
            Stage.SPEAKING -> "第 $currentPosition/$currentTotal 条：朗读中"
            Stage.LISTEN_DECISION -> "第 $currentPosition/$currentTotal 条：要回吗？"
            Stage.LISTEN_REPLY -> "正在听内容" + (message?.let { "（${it.sender}）" } ?: "")
            Stage.LISTEN_CONFIRM -> "待确认：$draft"
            Stage.BUSY -> "处理中…"
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("海参的唤醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityIntent())
            .addAction(0, "重听", serviceIntent(ACTION_REPEAT, 1))
            .addAction(0, "取消", serviceIntent(ACTION_CANCEL, 2))
            .addAction(0, "停止", serviceIntent(ACTION_STOP, 3))
            .build()

    private fun notifyFailure(text: String, replyText: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("回复没有发出")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(false)
            .setContentIntent(activityIntent())
            .addAction(0, "重试", serviceIntent(ACTION_RETRY_SEND, 5))
            .addAction(0, "复制并打开QQ", fallbackIntent(replyText))
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(ALERT_NOTIF_ID, builder.build())
        }
    }

    private fun fallbackIntent(text: String): PendingIntent {
        val intent = Intent(this, FallbackActivity::class.java)
            .putExtra(FallbackActivity.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            6,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun activityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RideService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openQq() {
        runCatching {
            val intent = packageManager.getLaunchIntentForPackage(QQListener.QQ_PACKAGE)
                ?: packageManager.getLaunchIntentForPackage(QQListener.QQ_LITE_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
    }

    private fun startForegroundSafely(text: String) {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val status = NotificationChannel(
            CHANNEL_STATUS,
            "海参的唤醒 · 状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        val alert = NotificationChannel(
            CHANNEL_ALERT,
            "回复提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(status)
        manager.createNotificationChannel(alert)
    }

    private fun normalize(value: String): String =
        value.replace(Regex("[\\s，。！？、,.!?;；:：\"'“”‘’~～…—\\-]"), "").lowercase()

    private fun stripReplyCommand(value: String): String {
        var text = value
        for (prefix in REPLY_PREFIXES) {
            if (text.startsWith(prefix)) {
                text = text.removePrefix(prefix)
                break
            }
        }
        if (text.startsWith("一下")) text = text.removePrefix("一下")
        for (suffix in listOf("确认发送", "确认发", "确认", "发送", "发出去", "发吧")) {
            if (text.endsWith(suffix) && text.length > suffix.length) {
                text = text.removeSuffix(suffix)
                break
            }
        }
        return text
    }

    private fun isNegative(value: String): Boolean =
        NEGATIVE_WORDS.any { value.contains(it) }

    private fun isConfirm(value: String): Boolean =
        value.length <= 12 && CONFIRM_WORDS.any { value.contains(it) }
}
