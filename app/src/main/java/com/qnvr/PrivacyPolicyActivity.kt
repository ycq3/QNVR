package com.qnvr

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scrollView.addView(container)
        setContentView(scrollView)

        val title = TextView(this).apply {
            text = "隐私政策与用户协议"
            textSize = 22f
            setTextColor(android.graphics.Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        container.addView(title)

        val content = TextView(this).apply {
            text = buildString {
                appendLine("最后更新日期：2026年07月24日")
                appendLine()
                appendLine("1. 信息收集与使用")
                appendLine("本应用（AI看家 / QNVR）是一款本地网络视频监控工具。我们将您的Android设备转换为RTSP网络摄像头，视频流仅在您的本地局域网内传输。我们不会收集、存储或上传您的任何视频、音频或图像数据到外部服务器。")
                appendLine()
                appendLine("2. 所需权限说明")
                appendLine("• 相机权限：用于采集实时视频画面，这是本应用的核心功能。")
                appendLine("• 录音权限：用于采集音频并随视频流一起传输，您可以在设置中关闭音频。")
                appendLine("• 网络权限：用于在局域网内提供RTSP视频流和Web配置界面。")
                appendLine("• 后台运行权限：用于保持视频服务在后台持续运行，确保监控不中断。")
                appendLine("• 唤醒锁权限：防止设备在运行期间自动熄屏。")
                appendLine("• 通知权限：用于显示前台服务通知，表明服务正在运行。")
                appendLine()
                appendLine("3. 数据安全")
                appendLine("所有视频流数据仅在您的设备与同一局域网内的客户端之间传输。我们不会在云端存储您的任何数据。您可以通过设置用户名和密码来保护RTSP流和Web界面的访问。")
                appendLine()
                appendLine("4. 第三方服务")
                appendLine("本应用集成了Sentry用于崩溃报告和性能监控，以帮助改进应用稳定性。Sentry可能会收集设备型号、应用版本和崩溃日志等信息，但不会包含您的视频内容。")
                appendLine()
                appendLine("5. 用户控制")
                appendLine("您可以随时在应用设置中调整视频参数、关闭音频、修改访问凭据或完全停止服务。卸载应用将删除所有本地配置数据。")
                appendLine()
                appendLine("6. 联系我们")
                appendLine("如有任何隐私相关的问题或建议，请通过应用内的反馈渠道或官方文档页面联系我们。")
                appendLine()
                appendLine("7. 协议同意")
                appendLine("使用本应用即表示您同意本隐私政策的条款。如果您不同意，请卸载本应用并停止使用。")
            }
            textSize = 14f
            setTextColor(android.graphics.Color.DKGRAY)
            setLineSpacing(0f, 1.6f)
        }
        container.addView(content)

        val btnBack = Button(this).apply {
            text = "返回"
            setPadding(0, 24, 0, 0)
            setOnClickListener { finish() }
        }
        container.addView(btnBack)
    }
}
