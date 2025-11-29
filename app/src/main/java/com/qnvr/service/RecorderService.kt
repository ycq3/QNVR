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
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QNVR:Recorder")
    wakeLock.acquire()

    cfg = ConfigStore(this)
    camera = CameraController(this)
    mjpegStreamer = MjpegStreamer(camera)
    httpServer = HttpServer(this, camera, mjpegStreamer, cfg, this)
    rtspServer = RtspServerWrapper(this, camera, cfg.getPort(), cfg.getUsername(), cfg.getPassword(), cfg.getWidth(), cfg.getHeight(), 30, cfg.getBitrate())

    camera.setWatermarkEnabled(true)
    camera.setZoom(1.0f)
    camera.setDeviceName(cfg.getDeviceName())
    camera.setShowDeviceName(cfg.isShowDeviceName())

    try { httpServer.begin() } catch (e: Exception) { Sentry.captureException(e) }
    try { mjpegStreamer.start() } catch (e: Exception) { Sentry.captureException(e) }

    val camPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (camPerm) {
      try { 
        camera.start() 
        
      } catch (e: Exception) { Sentry.captureException(e) }
      try { rtspServer.start() } catch (e: Exception) { Sentry.captureException(e) }
    } else {
      Sentry.captureMessage("Camera permission missing; skipping camera/rtsp start")
    }

    startForeground(1, buildNotification())
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
    try { rtspServer = RtspServerWrapper(this, camera, port, cfg.getUsername(), cfg.getPassword(), cfg.getWidth(), cfg.getHeight(), 30, cfg.getBitrate()); rtspServer.start() } catch (e: Exception) { Sentry.captureException(e) }
  }

  override fun applyEncoder(width: Int, height: Int, bitrate: Int) {
    try { rtspServer.updateEncoder(width, height, 30, bitrate) } catch (e: Exception) { Sentry.captureException(e) }
  }

  override fun applyDeviceName(name: String) { try { camera.setDeviceName(name) } catch (_: Exception) {} }
  override fun applyShowDeviceName(show: Boolean) { try { camera.setShowDeviceName(show) } catch (_: Exception) {} }
  override fun applyCredentials(username: String, password: String) { try { rtspServer.updateCredentials(username, password) } catch (_: Exception) {} }
}
