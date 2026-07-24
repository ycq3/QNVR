package com.qnvr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        scrollView.addView(container)
        setContentView(scrollView)

        val title = TextView(this).apply {
            text = "关于 AI看家"
            textSize = 24f
            setTextColor(android.graphics.Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        container.addView(title)

        val versionInfo = TextView(this).apply {
            text = "版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            textSize = 16f
            setTextColor(android.graphics.Color.DKGRAY)
            setPadding(0, 0, 0, 16)
        }
        container.addView(versionInfo)

        val desc = TextView(this).apply {
            text = "AI看家 (QNVR) 是一款将您的 Android 设备转换为专业网络摄像头的应用。\n\n" +
                   "支持 RTSP 流媒体传输、H.264/H.265 硬件编码、实时 Web 配置、自适应码率、低功耗模式等高级功能。\n\n" +
                   "所有视频数据仅在本地网络传输，确保您的隐私安全。"
            textSize = 15f
            setTextColor(android.graphics.Color.DKGRAY)
            setLineSpacing(0f, 1.6f)
            setPadding(0, 0, 0, 24)
        }
        container.addView(desc)

        val btnPrivacy = Button(this).apply {
            text = "隐私政策"
            setOnClickListener {
                startActivity(Intent(this@AboutActivity, PrivacyPolicyActivity::class.java))
            }
        }
        container.addView(btnPrivacy)

        val btnRate = Button(this).apply {
            text = "给我们评分"
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                    startActivity(intent)
                }
            }
        }
        container.addView(btnRate)

        val btnFeedback = Button(this).apply {
            text = "反馈与建议"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_SUBJECT, "AI看家 用户反馈 (v${BuildConfig.VERSION_NAME})")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("support@qnvr.app"))
                }
                startActivity(Intent.createChooser(intent, "发送反馈"))
            }
        }
        container.addView(btnFeedback)

        val btnBack = Button(this).apply {
            text = "返回"
            setPadding(0, 24, 0, 0)
            setOnClickListener { finish() }
        }
        container.addView(btnBack)
    }
}
