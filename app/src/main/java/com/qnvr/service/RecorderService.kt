package com.qnvr.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.qnvr.MainActivity
import com.qnvr.camera.CameraController
import com.qnvr.preview.MjpegStreamer
import com.qnvr.stream.RtspServerWrapper
import com.qnvr.web.HttpServer

class RecorderService : LifecycleService() {
  private lateinit var wakeLock: PowerManager.WakeLock
  private lateinit var camera: CameraController
  private lateinit var httpServer: HttpServer
  private lateinit var mjpegStreamer: MjpegStreamer
  private lateinit var rtspServer: RtspServerWrapper

  override fun onCreate() {
    super.onCreate()
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QNVR:Recorder")
    wakeLock.acquire()

    camera = CameraController(this)
    mjpegStreamer = MjpegStreamer(camera)
    httpServer = HttpServer(this, camera, mjpegStreamer)
    rtspServer = RtspServerWrapper(this, camera)

    httpServer.begin()
    mjpegStreamer.start()
    camera.start()
    rtspServer.start()

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
}
