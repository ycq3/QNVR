package com.qnvr

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.qnvr.config.ConfigStore
import com.qnvr.service.RecorderService
import com.qnvr.util.SettingsManager
import io.sentry.Sentry
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*

class MainActivity : ComponentActivity() {
  private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    val granted = result[Manifest.permission.CAMERA] == true &&
      result[Manifest.permission.INTERNET] == true &&
      result[Manifest.permission.WAKE_LOCK] == true
    if (granted) {
      startService()
    } else {
      Sentry.captureMessage("Permissions not granted; service not started")
      showPermissionExplanationDialog()
    }
  }

  private lateinit var statusText: TextView
  private lateinit var tvFps: TextView
  private lateinit var tvCpu: TextView
  private lateinit var tvNetwork: TextView
  private lateinit var tvEncoder: TextView
  private lateinit var tvCpuTemp: TextView
  private lateinit var tvBatteryTemp: TextView
  private lateinit var tvBatteryLevel: TextView
  private lateinit var cbAutoStart: CheckBox
  private lateinit var cbBootStart: CheckBox
  private lateinit var btnAbout: Button

  private val configChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
    displayIpAddressAndPort(statusText)
  }
  
  private val handler = Handler(Looper.getMainLooper())
  private val updateStatsRunnable = object : Runnable {
    override fun run() {
      updateStatsDisplay()
      handler.postDelayed(this, 1000)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContentView(R.layout.activity_main)
    Sentry.captureMessage("MainActivity onCreate")

    val start = findViewById<Button>(R.id.btnStart)
    val stop = findViewById<Button>(R.id.btnStop)
    statusText = findViewById<TextView>(R.id.tvStatus)
    tvFps = findViewById<TextView>(R.id.tvFps)
    tvCpu = findViewById<TextView>(R.id.tvCpu)
    tvNetwork = findViewById<TextView>(R.id.tvNetwork)
    tvEncoder = findViewById<TextView>(R.id.tvEncoder)
    tvCpuTemp = findViewById<TextView>(R.id.tvCpuTemp)
    tvBatteryTemp = findViewById<TextView>(R.id.tvBatteryTemp)
    tvBatteryLevel = findViewById<TextView>(R.id.tvBatteryLevel)
    cbAutoStart = findViewById<CheckBox>(R.id.cbAutoStart)
    cbBootStart = findViewById<CheckBox>(R.id.cbBootStart)
    btnAbout = findViewById<Button>(R.id.btnAbout)

    val sp = getSharedPreferences("qnvr", Context.MODE_PRIVATE)
    sp.registerOnSharedPreferenceChangeListener(configChangeListener)

    if (!sp.getBoolean("privacy_accepted", false)) {
      showPrivacyDialog(sp)
    }

    cbAutoStart.isChecked = SettingsManager.isAutoStartEnabled(this)
    cbBootStart.isChecked = SettingsManager.isBootStartEnabled(this)

    cbAutoStart.setOnCheckedChangeListener { _, isChecked ->
      SettingsManager.setAutoStartEnabled(this, isChecked)
    }
    cbBootStart.setOnCheckedChangeListener { _, isChecked ->
      SettingsManager.setBootStartEnabled(this, isChecked)
    }

    displayIpAddressAndPort(statusText)

    start.setOnClickListener { ensurePermissionsAndStart() }
    stop.setOnClickListener { stopService() }
    btnAbout.setOnClickListener {
      startActivity(Intent(this, AboutActivity::class.java))
    }

    handler.post(updateStatsRunnable)
    maybeAskForReview(sp)
  }

  private fun maybeAskForReview(sp: android.content.SharedPreferences) {
    val firstLaunch = sp.getLong("first_launch_time", 0L)
    val now = System.currentTimeMillis()
    if (firstLaunch == 0L) {
      sp.edit().putLong("first_launch_time", now).apply()
      return
    }
    val daysSinceFirstLaunch = (now - firstLaunch) / (1000 * 60 * 60 * 24)
    if (daysSinceFirstLaunch >= 3 && !sp.getBoolean("review_dismissed", false)) {
      AlertDialog.Builder(this)
        .setTitle("喜欢 AI看家 吗？")
        .setMessage("如果您觉得这款应用对您有帮助，请在应用商店给我们评分，这将激励我们持续改进！")
        .setPositiveButton("去评分") { _, _ ->
          sp.edit().putBoolean("review_dismissed", true).apply()
          try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
          } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
          }
        }
        .setNegativeButton("暂不") { _, _ ->
          sp.edit().putBoolean("review_dismissed", true).apply()
        }
        .setNeutralButton("稍后再说") { _, _ ->
          sp.edit().putLong("first_launch_time", now - (1000 * 60 * 60 * 24 * 2)).apply()
        }
        .show()
    }
  }

  override fun onResume() {
    super.onResume()
    if (SettingsManager.isAutoStartEnabled(this)) {
      handler.postDelayed({
        val service = RecorderService.getInstance()
        if (service != null) {
          service.retryStartCameraIfNeeded()
        } else {
          ensurePermissionsAndStart()
        }
      }, 600)
    }
  }
  
  private fun updateStatsDisplay() {
    val service = RecorderService.getInstance()
    if (service != null) {
      val stats = service.getStats()
      tvFps.text = "帧数: ${stats.currentFps} FPS"
      tvCpu.text = "CPU: %.1f%%".format(stats.cpuUsage)
      tvNetwork.text = "网络: 上传 %.1f Kbps / 下载 %.1f Kbps".format(stats.networkTxKbps, stats.networkRxKbps)
      if (stats.encoderName.isNotEmpty()) {
        tvEncoder.text = "编码器: ${stats.encoderName} (${stats.width}x${stats.height} @ ${stats.bitrate / 1000} Kbps)"
      } else {
        tvEncoder.text = "编码器: 未启动"
      }
      tvCpuTemp.text = if (stats.cpuTemp > 0) "CPU温度: %.1f°C".format(stats.cpuTemp) else "CPU温度: --°C"
      tvBatteryTemp.text = if (stats.batteryTemp > 0) "电池温度: %.1f°C".format(stats.batteryTemp) else "电池温度: --°C"
      tvBatteryLevel.text = if (stats.batteryLevel > 0) "电池电量: ${stats.batteryLevel}%" else "电池电量: --%"
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    try {
      val sp = getSharedPreferences("qnvr", Context.MODE_PRIVATE)
      sp.unregisterOnSharedPreferenceChangeListener(configChangeListener)
    } catch (_: Exception) {}
    handler.removeCallbacks(updateStatsRunnable)
  }

  private fun displayIpAddressAndPort(textView: TextView) {
    Thread {
      try {
        val ipAddress = getIpAddress()
        val cfg = ConfigStore(this)
        val port = cfg.getPort()
        val username = cfg.getUsername()
        val password = cfg.getPassword()
        val webPort = 8080
        
        runOnUiThread {
          val encodedUsername = java.net.URLEncoder.encode(username, "UTF-8")
          val encodedPassword = java.net.URLEncoder.encode(password, "UTF-8")
          val rtspUrl = if (encodedPassword.isNotEmpty()) {
            "rtsp://$encodedUsername:$encodedPassword@$ipAddress:$port/live"
          } else {
            "rtsp://$ipAddress:$port/live"
          }
          val versionInfo = "版本: ${BuildConfig.VERSION_NAME}"
          textView.text = "RTSP地址: $rtspUrl\nWeb界面: http://$ipAddress:$webPort/\n$versionInfo"
        }
      } catch (e: Exception) {
        runOnUiThread {
          textView.text = "无法获取IP地址信息"
        }
      }
    }.start()
  }

  private fun getIpAddress(): String {
    try {
      val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
      while (interfaces.hasMoreElements()) {
        val networkInterface: NetworkInterface = interfaces.nextElement()
        
        if (networkInterface.isLoopback || !networkInterface.isUp) {
          continue
        }
        
        val addresses: Enumeration<InetAddress> = networkInterface.inetAddresses
        while (addresses.hasMoreElements()) {
          val inetAddress: InetAddress = addresses.nextElement()
          val hostAddress = inetAddress.hostAddress
          
          if (hostAddress != null && !inetAddress.isLoopbackAddress && hostAddress.indexOf(':') == -1) {
            return hostAddress
          }
        }
      }
    } catch (e: Exception) {
      android.util.Log.e("MainActivity", "Error getting IP address", e)
    }
    return "127.0.0.1"
  }

  private fun ensurePermissionsAndStart() {
    val required = mutableListOf(
      Manifest.permission.CAMERA,
      Manifest.permission.INTERNET,
      Manifest.permission.RECORD_AUDIO,
      Manifest.permission.WAKE_LOCK
    )
    if (Build.VERSION.SDK_INT >= 33) {
      required.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isNotEmpty()) {
      requestPermissions.launch(missing.toTypedArray())
    } else {
      startService()
    }
  }

  private fun startService() {
    val service = RecorderService.getInstance()
    if (service != null) {
      service.retryStartCameraIfNeeded()
      return
    }
    val intent = Intent(this, RecorderService::class.java)
    ContextCompat.startForegroundService(this, intent)
  }

  private fun stopService() {
    RecorderService.stopManually()
    val intent = Intent(this, RecorderService::class.java)
    stopService(intent)
  }

  private fun showPrivacyDialog(sp: android.content.SharedPreferences) {
    AlertDialog.Builder(this)
      .setTitle("欢迎使用 AI看家")
      .setMessage("本应用需要将您的设备作为网络摄像头使用，因此需要以下权限：\n\n• 相机权限：用于采集视频画面\n• 录音权限：用于采集音频（可选）\n• 网络权限：用于传输视频流\n• 后台运行权限：用于保持服务持续运行\n\n您的视频数据仅在本地网络传输，我们不会收集、存储或上传您的任何视频内容到外部服务器。\n\n请阅读并同意《隐私政策》和《用户协议》后继续使用。")
      .setCancelable(false)
      .setPositiveButton("同意并继续") { _, _ ->
        sp.edit().putBoolean("privacy_accepted", true).apply()
        requestBatteryOptimizationWhitelist()
        ensurePermissionsAndStart()
      }
      .setNegativeButton("退出") { _, _ ->
        finish()
      }
      .setNeutralButton("查看隐私政策") { _, _ ->
        try {
          val intent = Intent(this, Class.forName("com.qnvr.PrivacyPolicyActivity"))
          startActivity(intent)
        } catch (e: Exception) {
          Toast.makeText(this, "隐私政策页面即将上线", Toast.LENGTH_SHORT).show()
        }
      }
      .show()
  }

  private fun requestBatteryOptimizationWhitelist() {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
      val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
      }
      startActivity(intent)
    }
  }

  private fun showPermissionExplanationDialog() {
    AlertDialog.Builder(this)
      .setTitle("权限说明")
      .setMessage("本应用需要相机、录音、网络和后台运行权限才能将您的设备作为网络摄像头使用。请在设置中开启相应权限。")
      .setPositiveButton("去设置") { _, _ ->
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
      }
      .setNegativeButton("取消", null)
      .show()
  }
}
