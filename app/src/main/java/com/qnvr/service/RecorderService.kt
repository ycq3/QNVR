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
import io.sentry.Sentry

class RecorderService : LifecycleService(), ConfigApplier {
  private lateinit var wakeLock: PowerManager.WakeLock
  private lateinit var camera: CameraController
  private lateinit var httpServer: HttpServer
  private lateinit var mjpegStreamer: MjpegStreamer
  private lateinit var rtspServer: RtspServerWrapper
  private lateinit var cfg: ConfigStore

  override fun onCreate() {
    super.onCreate()
    android.util.Log.i("RecorderService", "onCreate called")
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QNVR:Recorder")
    wakeLock.acquire()

    cfg = ConfigStore(this)
    camera = CameraController(this)
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
    
    android.util.Log.i("RecorderService", "Creating RTSP server with port: ${cfg.getPort()}, username: ${cfg.getUsername()}, password: ${cfg.getPassword()}, encoderName: ${cfg.getEncoderName()}, mimeType: $mimeType, bitrate: $bitrate")
    
    try {
      rtspServer = RtspServerWrapper(
          this, 
          camera, 
          cfg.getPort(), 
          cfg.getUsername(), 
          cfg.getPassword(), 
          cfg.getWidth(), 
          cfg.getHeight(), 
          30, 
          bitrate,  // 使用验证后的码率
          cfg.getEncoderName(),  // 使用配置的编码器名称
          mimeType  // 使用验证后的MIME类型
      )
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
        // 不抛出异常，继续尝试启动RTSP服务器
      }
      
      try { 
        android.util.Log.i("RecorderService", "Starting RTSP server")
        rtspServer.start() 
        android.util.Log.i("RecorderService", "RTSP server started successfully")
      } catch (e: Exception) { 
        android.util.Log.e("RecorderService", "Failed to start RTSP server", e)
        Sentry.captureException(e)
        // 这里我们记录错误但不抛出异常，让应用继续运行
      }
    } else {
      android.util.Log.w("RecorderService", "Camera permission missing; skipping camera/rtsp start")
      Sentry.captureMessage("Camera permission missing; skipping camera/rtsp start")
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
    try { rtspServer.stop() } catch (_: Exception) {}
    try { mjpegStreamer.stop() } catch (_: Exception) {}
    try { httpServer.shutdown() } catch (_: Exception) {}
    try { camera.stop() } catch (_: Exception) {}
    if (wakeLock.isHeld) wakeLock.release()
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
          30, 
          cfg.getBitrate(), 
          cfg.getEncoderName(),  // 使用配置的编码器名称
          cfg.getMimeType()      // 使用配置的MIME类型
      )
      rtspServer.start() 
    } catch (e: Exception) { Sentry.captureException(e) }
  }

  override fun applyEncoder(width: Int, height: Int, bitrate: Int) {
    try { 
      rtspServer.updateEncoder(
          width, 
          height, 
          30, 
          bitrate, 
          cfg.getEncoderName(),  // 使用配置的编码器名称
          cfg.getMimeType()      // 使用配置的MIME类型
      ) 
    } catch (e: Exception) { Sentry.captureException(e) }
  }

  override fun applyDeviceName(name: String) { try { camera.setDeviceName(name) } catch (_: Exception) {} }
  override fun applyShowDeviceName(show: Boolean) { try { camera.setShowDeviceName(show) } catch (_: Exception) {} }
  override fun applyCredentials(username: String, password: String) { try { rtspServer.updateCredentials(username, password) } catch (_: Exception) {} }
}
