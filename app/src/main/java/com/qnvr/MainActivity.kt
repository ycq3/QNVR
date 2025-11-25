package com.qnvr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.qnvr.service.RecorderService
import io.sentry.Sentry

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

    start.setOnClickListener { ensurePermissionsAndStart() }
    stop.setOnClickListener { stopService() }
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
