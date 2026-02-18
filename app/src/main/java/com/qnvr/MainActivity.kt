package com.qnvr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.qnvr.config.ConfigStore
import com.qnvr.service.RecorderService
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

    val cfg = ConfigStore(this)
    val sp = getSharedPreferences("qnvr", Context.MODE_PRIVATE)
    sp.registerOnSharedPreferenceChangeListener(configChangeListener)

    displayIpAddressAndPort(statusText)

    start.setOnClickListener { ensurePermissionsAndStart() }
    stop.setOnClickListener { stopService() }
    
    handler.post(updateStatsRunnable)
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
          textView.text = "RTSP地址: $rtspUrl\nWeb界面: http://$ipAddress:$webPort/"
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
    val intent = Intent(this, RecorderService::class.java)
    ContextCompat.startForegroundService(this, intent)
  }

  private fun stopService() {
    val intent = Intent(this, RecorderService::class.java)
    stopService(intent)
  }
}
