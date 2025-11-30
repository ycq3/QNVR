package com.qnvr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // waiting for view to draw to better represent a captured error with a screenshot
    // findViewById<android.view.View>(android.R.id.content).viewTreeObserver.addOnGlobalLayoutListener {
    //   try {
    //     throw Exception("This app uses Sentry! :)")
    //   } catch (e: Exception) {
    //     Sentry.captureException(e)
    //   }
    // }

    setContentView(R.layout.activity_main)
    Sentry.captureMessage("MainActivity onCreate")

    val start = findViewById<Button>(R.id.btnStart)
    val stop = findViewById<Button>(R.id.btnStop)
    val statusText = findViewById<TextView>(R.id.tvStatus)

    // 显示IP地址和端口信息
    displayIpAddressAndPort(statusText)

    start.setOnClickListener { ensurePermissionsAndStart() }
    stop.setOnClickListener { stopService() }
  }

  private fun displayIpAddressAndPort(textView: TextView) {
    Thread {
      try {
        val ipAddress = getIpAddress()
        val port = 18554 // 默认RTSP端口
        val webPort = 8080 // 默认Web端口
        
        runOnUiThread {
          textView.text = "RTSP地址: rtsp://$ipAddress:$port/live\nWeb界面: http://$ipAddress:$webPort/"
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
        // 打印网络接口信息用于调试
        android.util.Log.d("MainActivity", "Network interface: ${networkInterface.name}, isUp: ${networkInterface.isUp}, isLoopback: ${networkInterface.isLoopback}")
        
        if (networkInterface.isLoopback || !networkInterface.isUp) {
          continue
        }
        
        val addresses: Enumeration<InetAddress> = networkInterface.inetAddresses
        while (addresses.hasMoreElements()) {
          val inetAddress: InetAddress = addresses.nextElement()
          val hostAddress = inetAddress.hostAddress
          // 打印IP地址信息用于调试
          android.util.Log.d("MainActivity", "IP Address: $hostAddress, isLoopback: ${inetAddress.isLoopbackAddress}, hasColon: ${hostAddress?.indexOf(':')}")
          
          if (hostAddress != null && !inetAddress.isLoopbackAddress && hostAddress.indexOf(':') == -1) {
            android.util.Log.d("MainActivity", "Selected IP Address: $hostAddress")
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