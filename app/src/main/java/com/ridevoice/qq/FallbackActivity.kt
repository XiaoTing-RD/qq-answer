package com.ridevoice.qq

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class FallbackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "fallback_text"
    }

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        window.decorView.postDelayed({
            copyAndOpen()
        }, 220L)
    }

    private fun copyAndOpen() {
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (text.isNotEmpty()) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            runCatching {
                clipboard?.setPrimaryClip(ClipData.newPlainText("QQ回复", text))
            }
        }
        val launch = packageManager.getLaunchIntentForPackage(QQListener.QQ_PACKAGE)
            ?: packageManager.getLaunchIntentForPackage(QQListener.QQ_LITE_PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            Toast.makeText(this, "回复已复制，去 QQ 粘贴发送", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "没有检测到 QQ", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
