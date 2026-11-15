package com.ridevoice.qq

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ridevoice.qq.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var speaker: Speaker? = null
    private var musicAlarm: MusicAlarm? = null
    private var testVoiceInput: VoiceInput? = null
    private var updating = false

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("需要麦克风权限才能语音回复")
            updateUi()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            updateUi()
        }

    private val pickMusic =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            prefs.musicUri = uri.toString()
            prefs.musicName = queryDisplayName(uri)
            prefs.musicEnabled = true
            updateUi()
            toast("已选择并开启：${prefs.musicName}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        setupUi()
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    override fun onDestroy() {
        testVoiceInput?.destroy()
        testVoiceInput = null
        speaker?.shutdown()
        speaker = null
        musicAlarm?.stop()
        musicAlarm = null
        super.onDestroy()
    }

    private fun setupUi() {
        binding.btnListener.setOnClickListener { openNotificationAccess() }
        binding.btnMic.setOnClickListener {
            if (hasMic()) toast("麦克风已授权") else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        binding.btnNotif.setOnClickListener {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                toast("通知已开启")
            } else if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openSelfNotificationSettings()
            }
        }
        binding.btnBattery.setOnClickListener { openBatterySettings() }
        binding.btnQqNotifications.setOnClickListener { openQqNotificationSettings() }
        binding.btnTtsSettings.setOnClickListener { openTtsSettings() }
        binding.btnTestVoice.setOnClickListener { testVoice() }
        binding.btnPickMusic.setOnClickListener { pickMusic.launch(arrayOf("audio/*")) }
        binding.btnTestMusic.setOnClickListener { testMusic() }
        binding.btnTestAsr.setOnClickListener { startAsrTest() }

        binding.switchActive.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            if (checked) startRide() else stopRide()
        }
        binding.switchGroups.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            prefs.readGroups = checked
        }
        binding.switchMusic.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            prefs.musicEnabled = checked
            if (checked && prefs.musicUri.isBlank()) {
                toast("还没选音乐，点「选择本地音乐」")
            }
            updateUi()
        }
        binding.switchOnlyContact.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            if (checked) {
                val name = binding.editContact.text.toString().trim()
                if (name.isEmpty()) {
                    toast("先填写只朗读的好友名字")
                    updating = true
                    binding.switchOnlyContact.isChecked = false
                    updating = false
                    updateUi()
                    return@setOnCheckedChangeListener
                }
                prefs.contactName = name
            }
            prefs.onlyContact = checked
            updateUi()
        }

        binding.editContact.setText(prefs.contactName)
        binding.btnSaveContact.setOnClickListener { saveContact() }
        binding.editContact.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveContact()
                true
            } else {
                false
            }
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            VoicePreset.values().map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerPreset.adapter = adapter
        binding.spinnerPreset.setSelection(prefs.presetIndex, false)
        binding.spinnerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (updating) return
                prefs.presetIndex = position
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    private fun updateUi() {
        updating = true
        try {
            val listener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
            val mic = hasMic()
            val notifications = NotificationManagerCompat.from(this).areNotificationsEnabled()
            val battery = getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(packageName) == true

            setStatus(binding.listenerStatus, listener)
            setStatus(binding.micStatus, mic)
            setStatus(binding.notifStatus, notifications)
            setStatus(binding.batteryStatus, battery)
            binding.serviceStatus.text = if (RideService.instance != null) "运行中" else "未运行"
            binding.switchActive.isChecked = RideService.instance != null
            binding.switchGroups.isChecked = prefs.readGroups
            binding.switchOnlyContact.isChecked = prefs.onlyContact
            binding.switchMusic.isChecked = prefs.musicEnabled
            binding.spinnerPreset.setSelection(prefs.presetIndex, false)
            binding.tvSubtitle.text = if (prefs.onlyContact) {
                "只读 ${prefs.contactName} · 语音回复 · 落樱模式"
            } else {
                "所有好友 · 语音回复 · 落樱模式"
            }
            binding.contactHint.text = if (prefs.onlyContact) {
                "只处理「${prefs.contactName}」的消息；名字必须和 QQ 通知标题里的备注/昵称完全一致。"
            } else {
                "过滤已关闭：所有好友的消息都会朗读和回复。"
            }
            binding.musicName.text = if (prefs.musicUri.isBlank()) {
                "还没选择音乐"
            } else {
                prefs.musicName.ifBlank { "已选择的本地音乐" }
            }
            binding.btnTestMusic.isEnabled = prefs.musicUri.isNotBlank()
            binding.tvQqState.text = if (isQqInstalled()) "已检测到 QQ" else "未检测到 QQ"

            val asrAvailable = SpeechRecognizer.isRecognitionAvailable(this)
            val voiceState = when {
                !mic -> "语音识别：需要麦克风权限"
                !asrAvailable -> "语音识别：不可用"
                else -> "语音识别：可用"
            }
            binding.voiceStatus.text = voiceState
            binding.voiceStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (mic && asrAvailable) R.color.ok else R.color.warn
                )
            )
            binding.voiceLastError.text =
                "最近错误：" + prefs.lastVoiceError.ifBlank { "无" }
        } finally {
            updating = false
        }
    }

    private fun setStatus(view: TextView, granted: Boolean) {
        view.text = if (granted) "已授权" else "未授权"
        view.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.ok else R.color.warn)
        )
    }

    private fun startRide() {
        if (!hasMic()) {
            updating = true
            binding.switchActive.isChecked = false
            updating = false
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val intent = Intent(this, RideService::class.java).setAction(RideService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        binding.serviceStatus.text = "正在启动…"
        binding.root.postDelayed({ updateUi() }, 700)
        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            toast("还没给通知使用权，将无法读取 QQ 消息")
        }
    }

    private fun stopRide() {
        val intent = Intent(this, RideService::class.java).setAction(RideService.ACTION_STOP)
        runCatching { startService(intent) }
        binding.serviceStatus.text = "正在停止…"
        binding.root.postDelayed({ updateUi() }, 500)
    }

    private fun testVoice() {
        val player = speaker ?: Speaker(this).also { speaker = it }
        val preset = prefs.preset
        player.speak(
            "你好呀，我是海参的唤醒，好友消息会这样读给你听。",
            preset.pitch,
            preset.rate
        )
    }

    private fun testMusic() {
        val uri = prefs.musicUri
        if (uri.isBlank()) {
            toast("先选择一首本地音乐")
            return
        }
        val alarm = musicAlarm ?: MusicAlarm(this).also { musicAlarm = it }
        alarm.play(uri, 6000L) { }
        toast("正在试听提醒音乐")
    }

    private fun startAsrTest() {
        if (!hasMic()) {
            toast("先授权麦克风，再测试语音识别")
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val message = "本机没有可用的语音识别服务"
            binding.asrResult.text = "失败：$message"
            prefs.lastVoiceError = message
            binding.voiceLastError.text = "最近错误：$message"
            updateUi()
            return
        }
        testVoiceInput?.destroy()
        val input = VoiceInput(this)
        testVoiceInput = input
        binding.btnTestAsr.isEnabled = false
        binding.asrResult.text = "正在听，请说一句话…"
        input.listen(15000L) { result ->
            if (isFinishing || isDestroyed) return@listen
            binding.btnTestAsr.isEnabled = true
            if (result.ok) {
                binding.asrResult.text = "识别成功：${result.text}"
                prefs.lastVoiceError = ""
            } else {
                binding.asrResult.text = "失败：${result.label}"
                prefs.lastVoiceError = result.label
            }
            testVoiceInput = null
            updateUi()
        }
    }

    private fun saveContact() {
        val name = binding.editContact.text.toString().trim()
        if (name.isEmpty()) {
            toast("好友名字不能为空")
            return
        }
        prefs.contactName = name
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.editContact.windowToken, 0)
        toast("已保存：$name")
        updateUi()
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = ""
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index) ?: ""
                }
            }
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "本地音乐"
        }
        return name
    }

    private fun openNotificationAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                intent.putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, QQListener::class.java).flattenToString()
                )
                startActivity(intent)
                return
            }
        } catch (_: Exception) {
        }
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            toast("请到系统设置里手动开启通知使用权")
        }
    }

    private fun openSelfNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (_: Exception) {
            toast("请到系统设置里打开通知")
        }
    }

    private fun openQqNotificationSettings() {
        if (!isQqInstalled()) {
            toast("没有检测到 QQ")
            return
        }
        val pkg = if (isInstalled(QQListener.QQ_PACKAGE)) {
            QQListener.QQ_PACKAGE
        } else {
            QQListener.QQ_LITE_PACKAGE
        }
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            )
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$pkg")
                    )
                )
            } catch (_: Exception) {
                toast("请手动打开 QQ 的通知设置")
            }
        }
    }

    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            toast("已忽略电池优化")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("请到电池设置里允许后台运行")
            }
        }
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            toast("请到系统设置 → 语言和输入 → 文字转语音 中设置")
        }
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isQqInstalled(): Boolean =
        isInstalled(QQListener.QQ_PACKAGE) || isInstalled(QQListener.QQ_LITE_PACKAGE)

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
