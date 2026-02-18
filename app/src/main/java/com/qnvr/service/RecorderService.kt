package com.qnvr.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.qnvr.MainActivity
import com.qnvr.camera.CameraController
import com.qnvr.preview.MjpegStreamer
import com.qnvr.stream.RtspServerWrapper
import com.qnvr.web.HttpServer
import com.qnvr.config.ConfigStore
import com.qnvr.config.ConfigApplier
import com.qnvr.receiver.ServiceRestartReceiver
import com.qnvr.util.SettingsManager
import io.sentry.Sentry
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.URL

class RecorderService : LifecycleService(), ConfigApplier, SharedPreferences.OnSharedPreferenceChangeListener {
  private lateinit var wakeLock: PowerManager.WakeLock
  private lateinit var camera: CameraController
  private lateinit var httpServer: HttpServer
  private lateinit var mjpegStreamer: MjpegStreamer
  private lateinit var rtspServer: RtspServerWrapper
  private lateinit var cfg: ConfigStore
  private lateinit var statsMonitor: com.qnvr.StatsMonitor
  private lateinit var sp: SharedPreferences
  
  private var isStoppingManually = false
  
  companion object {
      private var instance: RecorderService? = null
      
      fun getInstance(): RecorderService? = instance
      
      fun stopManually() {
          instance?.isStoppingManually = true
      }
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
    android.util.Log.i("RecorderService", "onCreate called")
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QNVR:Recorder")
    wakeLock.acquire()

    cfg = ConfigStore(this)
    sp = getSharedPreferences("qnvr", Context.MODE_PRIVATE)
    sp.registerOnSharedPreferenceChangeListener(this)
    
    camera = CameraController(this)
    statsMonitor = com.qnvr.StatsMonitor(this)
    camera.setStatsMonitor(statsMonitor)
    mjpegStreamer = MjpegStreamer(camera)
    
    try {
      android.util.Log.i("RecorderService", "Initializing HTTP server")
      httpServer = HttpServer(this, camera, mjpegStreamer, cfg, this)
    } catch (e: Exception) {
      android.util.Log.e("RecorderService", "Failed to initialize HTTP server", e)
      Sentry.captureException(e)
      throw RuntimeException("Failed to initialize HTTP server", e)
    }
    
    // 验证配置并使用合适的默认值
    val mimeType = cfg.getMimeType()
    val bitrate = if (cfg.getBitrate() > 0) cfg.getBitrate() else cfg.getBitrateForMimeType(mimeType)
    val encoderName = cfg.getEncoderName() ?: "auto"
    
    android.util.Log.i("RecorderService", "Creating RTSP server with port: ${cfg.getPort()}, username: ${cfg.getUsername()}, password: ${cfg.getPassword()}, encoderName: $encoderName, mimeType: $mimeType, bitrate: $bitrate")
    
    try { 
        rtspServer = RtspServerWrapper(
            this, 
            camera, 
            cfg.getPort(), 
            cfg.getUsername(), 
            cfg.getPassword(), 
            cfg.getWidth(), 
            cfg.getHeight(), 
            cfg.getFps(), 
            bitrate,
            encoderName,
            mimeType,
            cfg.isAudioEnabled()
        )
        rtspServer.start()
        applyPushConfig(cfg.isPushEnabled(), cfg.getPushUrl(), cfg.isPushUseRemoteConfig(), cfg.getPushConfigUrl())
        
        val actualEncoderName = rtspServer.getActualEncoderName() ?: encoderName
        statsMonitor.setEncoderInfo(actualEncoderName, mimeType, cfg.getWidth(), cfg.getHeight(), bitrate)
    } catch (e: Exception) {
      android.util.Log.e("RecorderService", "Failed to create RTSP server", e)
      Sentry.captureException(e)
      throw RuntimeException("Failed to create RTSP server", e)
    }

    camera.setWatermarkEnabled(true)
    camera.setRtspWatermarkEnabled(true)  // 启用RTSP流水印
    camera.setZoom(1.0f)
    camera.setDeviceName(cfg.getDeviceName())
    camera.setShowDeviceName(cfg.isShowDeviceName())

    try { 
      android.util.Log.i("RecorderService", "Starting HTTP server")
      httpServer.begin() 
      android.util.Log.i("RecorderService", "HTTP server started successfully")
    } catch (e: Exception) { 
      android.util.Log.e("RecorderService", "Failed to start HTTP server", e)
      Sentry.captureException(e) 
      // 不抛出异常，继续尝试启动其他服务
    }
    
    try { 
      android.util.Log.i("RecorderService", "Starting MJPEG streamer")
      mjpegStreamer.start() 
      android.util.Log.i("RecorderService", "MJPEG streamer started successfully")
    } catch (e: Exception) { 
      android.util.Log.e("RecorderService", "Failed to start MJPEG streamer", e)
      Sentry.captureException(e) 
      // 不抛出异常，继续尝试启动其他服务
    }

    val camPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    android.util.Log.i("RecorderService", "Camera permission granted: $camPerm")
    
    if (camPerm) {
      try { 
        android.util.Log.i("RecorderService", "Starting camera")
        camera.start() 
        android.util.Log.i("RecorderService", "Camera started successfully")
      } catch (e: Exception) { 
        android.util.Log.e("RecorderService", "Failed to start camera", e)
        Sentry.captureException(e)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, "Camera failed to start: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
      }
    } else {
      android.util.Log.w("RecorderService", "Camera permission missing; skipping camera start")
      Sentry.captureMessage("Camera permission missing; skipping camera start")
    }

    startForeground(1, buildNotification())
    android.util.Log.i("RecorderService", "onCreate completed")
  }

  private fun buildNotification(): Notification {
    val channelId = "qnvr_recorder"
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      val channel = NotificationChannel(channelId, "QNVR", NotificationManager.IMPORTANCE_LOW)
      channel.enableLights(false)
      channel.enableVibration(false)
      channel.lightColor = Color.BLUE
      nm.createNotificationChannel(channel)
    }
    val intent = Intent(this, MainActivity::class.java)
    val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(this, channelId)
      .setSmallIcon(android.R.drawable.presence_video_online)
      .setContentTitle("QNVR 正在录像")
      .setContentText("RTSP 和 Web 已启动")
      .setContentIntent(pi)
      .setOngoing(true)
      .build()
  }

  override fun onDestroy() {
    super.onDestroy()
    instance = null
    try {
        if (::sp.isInitialized) {
            sp.unregisterOnSharedPreferenceChangeListener(this)
        }
    } catch (_: Exception) {}
    try { rtspServer.stop() } catch (_: Exception) {}
    try { mjpegStreamer.stop() } catch (_: Exception) {}
    try { httpServer.shutdown() } catch (_: Exception) {}
    try { camera.stop() } catch (_: Exception) {}
    if (wakeLock.isHeld) wakeLock.release()
    
    if (!isStoppingManually && SettingsManager.isBootStartEnabled(this)) {
      android.util.Log.i("RecorderService", "Service destroyed unexpectedly, scheduling restart")
      val restartIntent = Intent(ServiceRestartReceiver.ACTION_RESTART_SERVICE)
      restartIntent.setPackage(packageName)
      sendBroadcast(restartIntent)
    }
  }
  
  fun getStats(): com.qnvr.StatsData {
      return statsMonitor.getStats()
  }

  override fun onBind(intent: Intent): IBinder? {
    return super.onBind(intent)
  }

  override fun applyPort(port: Int) {
    try { rtspServer.stop() } catch (_: Exception) {}
    try { 
      rtspServer = RtspServerWrapper(
          this, 
          camera, 
          port, 
          cfg.getUsername(), 
          cfg.getPassword(), 
          cfg.getWidth(), 
          cfg.getHeight(), 
          cfg.getFps(), 
          cfg.getBitrate(), 
          cfg.getEncoderName(),
          cfg.getMimeType(),
          cfg.isAudioEnabled()
      )
      rtspServer.start() 
      applyPushConfig(cfg.isPushEnabled(), cfg.getPushUrl(), cfg.isPushUseRemoteConfig(), cfg.getPushConfigUrl())
    } catch (e: Exception) { Sentry.captureException(e) }
  }

  override fun applyEncoder(width: Int, height: Int, bitrate: Int, fps: Int) {
    try { 
      rtspServer.updateEncoder(
          width, 
          height, 
          fps, 
          bitrate, 
          cfg.getEncoderName(),  // 使用配置的编码器名称
          cfg.getMimeType()      // 使用配置的MIME类型
      ) 
    } catch (e: Exception) { Sentry.captureException(e) }
  }

  override fun applyDeviceName(name: String) { try { camera.setDeviceName(name) } catch (_: Exception) {} }
  override fun applyShowDeviceName(show: Boolean) { try { camera.setShowDeviceName(show) } catch (_: Exception) {} }
  override fun applyCredentials(username: String, password: String) { try { rtspServer.updateCredentials(username, password) } catch (_: Exception) {} }
  override fun applyPushConfig(enabled: Boolean, url: String?, useRemoteConfig: Boolean, configUrl: String?) {
    val resolved = resolvePushConfig(enabled, url, useRemoteConfig, configUrl)
    try { rtspServer.updatePushConfig(resolved.first, resolved.second) } catch (_: Exception) {}
  }

  private val configHandler = android.os.Handler(android.os.Looper.getMainLooper())
  private val configRunnable = Runnable {
      try {
          // Re-read all relevant config and apply
          val mimeType = cfg.getMimeType()
          val bitrate = if (cfg.getBitrate() > 0) cfg.getBitrate() else cfg.getBitrateForMimeType(mimeType)
          applyEncoder(cfg.getWidth(), cfg.getHeight(), bitrate, cfg.getFps())
      } catch (e: Exception) {
          android.util.Log.e("RecorderService", "Error applying config", e)
      }
  }

  override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
      if (key == null) return
      android.util.Log.i("RecorderService", "Config changed: $key")
      
      when (key) {
          "port" -> applyPort(cfg.getPort())
          "width", "height", "fps", "bitrate", "encoderName", "mimeType" -> {
              configHandler.removeCallbacks(configRunnable)
              configHandler.postDelayed(configRunnable, 500)
          }
          "deviceName" -> applyDeviceName(cfg.getDeviceName())
          "showDeviceName" -> applyShowDeviceName(cfg.isShowDeviceName())
          "username", "password" -> applyCredentials(cfg.getUsername(), cfg.getPassword())
          "pushEnabled", "pushUrl", "pushUseRemoteConfig", "pushConfigUrl" -> {
              applyPushConfig(cfg.isPushEnabled(), cfg.getPushUrl(), cfg.isPushUseRemoteConfig(), cfg.getPushConfigUrl())
          }
      }
  }

  private fun resolvePushConfig(enabled: Boolean, url: String?, useRemoteConfig: Boolean, configUrl: String?): Pair<Boolean, String?> {
    var finalEnabled = enabled
    var finalUrl = url
    if (useRemoteConfig && !configUrl.isNullOrBlank()) {
      val remote = fetchRemotePushConfig(configUrl)
      if (remote != null) {
        if (remote.has("enabled")) finalEnabled = remote.optBoolean("enabled", finalEnabled)
        val remoteUrl = when {
          remote.has("pushUrl") -> remote.optString("pushUrl", "")
          remote.has("url") -> remote.optString("url", "")
          else -> ""
        }
        if (remoteUrl.isNotBlank()) {
          val user = if (remote.has("username")) remote.optString("username", "") else ""
          val pass = if (remote.has("password")) remote.optString("password", "") else ""
          finalUrl = applyAuthToUrl(remoteUrl, user, pass)
        }
      }
    }
    return Pair(finalEnabled, finalUrl)
  }

  private fun fetchRemotePushConfig(url: String): JSONObject? {
    return try {
      val conn = URL(url).openConnection()
      conn.connectTimeout = 5000
      conn.readTimeout = 5000
      val text = conn.getInputStream().bufferedReader().use { it.readText() }
      JSONObject(text)
    } catch (_: Exception) {
      null
    }
  }

  private fun applyAuthToUrl(url: String, username: String, password: String): String {
    if (username.isBlank() && password.isBlank()) return url
    return try {
      val uri = java.net.URI(url)
      val userInfo = if (username.isNotBlank() || password.isNotBlank()) {
        "${java.net.URLEncoder.encode(username, "UTF-8")}:${java.net.URLEncoder.encode(password, "UTF-8")}"
      } else {
        uri.userInfo ?: ""
      }
      val host = uri.host ?: return url
      val port = if (uri.port > 0) ":${uri.port}" else ""
      val path = uri.rawPath ?: ""
      val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
      val userPart = if (userInfo.isNotBlank()) "$userInfo@" else ""
      "${uri.scheme}://$userPart$host$port$path$query"
    } catch (_: Exception) {
      url
    }
  }
}
