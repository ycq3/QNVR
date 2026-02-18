package com.qnvr.web

import android.content.Context
import com.qnvr.camera.CameraController
import com.qnvr.preview.MjpegStreamer
import com.qnvr.config.ConfigStore
import com.qnvr.config.ConfigApplier
import com.qnvr.service.RecorderService
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONObject
import java.util.HashMap

class HttpServer(ctx: Context, private val camera: CameraController, private val mjpeg: MjpegStreamer, private val cfg: ConfigStore, private val applier: ConfigApplier) : NanoHTTPD(8080) {
  private val assets = ctx.assets
  fun begin() { super.start(5000) }
  fun shutdown() { super.stop() }

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri
    if (uri == "/") return serveAssetHtml("web/index.html")
    if (uri == "/stream.mjpg") return serveMjpeg()
    if (uri == "/api/status") return serveStatus(session)
    if (uri == "/api/stats") return serveStats(session)
    if (uri == "/api/config" && session.method == Method.GET) return getConfig(session)
    if (uri == "/api/config" && session.method == Method.POST) return handleConfig(session)
    if (uri == "/api/encoders" && session.method == Method.GET) return getEncoders(session)  // 新增：获取编码器列表
    return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404")
  }
  
  private fun serveStats(@Suppress("UNUSED_PARAMETER") session: IHTTPSession): Response {
    val json = JSONObject()
    try {
      val service = com.qnvr.service.RecorderService.getInstance()
      if (service != null) {
        val stats = service.getStats()
        json.put("currentFps", stats.currentFps)
        json.put("cpuUsage", stats.cpuUsage)
        json.put("networkRxBytes", stats.networkRxBytes)
        json.put("networkTxBytes", stats.networkTxBytes)
        json.put("networkRxKbps", stats.networkRxKbps)
        json.put("networkTxKbps", stats.networkTxKbps)
        json.put("encoderName", stats.encoderName)
        json.put("encoderType", stats.encoderType)
        json.put("width", stats.width)
        json.put("height", stats.height)
        json.put("bitrate", stats.bitrate)
        json.put("cpuTemp", stats.cpuTemp)
        json.put("batteryTemp", stats.batteryTemp)
        json.put("batteryLevel", stats.batteryLevel)
      }
    } catch (e: Exception) {
      android.util.Log.e("HttpServer", "Error getting stats", e)
    }
    return newFixedLengthResponse(Status.OK, "application/json", json.toString())
  }

  private fun serveAssetHtml(path: String): Response {
    val stream = assets.open(path)
    val bytes = stream.readBytes()
    stream.close()
    return newFixedLengthResponse(Status.OK, "text/html", String(bytes))
  }

  private fun serveMjpeg(): Response {
    val boundary = "frame"
    val pos = java.io.PipedOutputStream()
    val pis = java.io.PipedInputStream(pos)
    Thread {
      while (true) {
        try {
          val img = mjpeg.nextFrame() ?: continue
          val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${img.size}\r\n\r\n"
          pos.write(header.toByteArray())
          pos.write(img)
          pos.write("\r\n".toByteArray())
          pos.flush()
          Thread.sleep(100)
        } catch (_: Exception) {}
      }
    }.start()
    val res = newChunkedResponse(Status.OK, "multipart/x-mixed-replace; boundary=$boundary", pis)
    res.addHeader("Connection", "close")
    res.setChunkedTransfer(true)
    return res
  }

  private fun serveStatus(session: IHTTPSession): Response {
    val ip = session.headers["host"] ?: "localhost"
    val host = ip.substringBefore(":")
    val encodedUsername = java.net.URLEncoder.encode(cfg.getUsername(), "UTF-8")
    val encodedPassword = java.net.URLEncoder.encode(cfg.getPassword(), "UTF-8")
    val rtsp = "rtsp://$encodedUsername:$encodedPassword@${host}:${cfg.getPort()}/live"
    
    val stats = RecorderService.getInstance()?.getStats()
    val json = JSONObject()
    json.put("rtsp", rtsp)
    json.put("web", "http://$ip/")
    json.put("deviceName", cfg.getDeviceName())
    json.put("showDeviceName", cfg.isShowDeviceName())
    json.put("bitrate", cfg.getBitrate())
    json.put("width", cfg.getWidth())
    json.put("height", cfg.getHeight())
    json.put("fps", cfg.getFps())
    json.put("port", cfg.getPort())
    json.put("currentFps", stats?.currentFps ?: 0)
    json.put("cpuUsage", (stats?.cpuUsage ?: 0f).toDouble())
    json.put("encoderName", stats?.encoderName ?: cfg.getEncoderName() ?: "")
    json.put("encoderType", stats?.encoderType ?: cfg.getMimeType() ?: "")
    return newFixedLengthResponse(Status.OK, "application/json", json.toString())
  }

  private fun handleConfig(session: IHTTPSession): Response {
    val body = HashMap<String, String>()
    session.parseBody(body)
    val data = body["postData"] ?: "{}"
    val json = JSONObject(data)
    if (json.has("torch")) camera.setTorch(json.getBoolean("torch"))
    if (json.has("zoom")) camera.setZoom(json.getDouble("zoom").toFloat())
    if (json.has("watermark")) camera.setWatermarkEnabled(json.getBoolean("watermark"))
    if (json.has("port")) { val p = json.getInt("port"); cfg.setPort(p); applier.applyPort(p) }
    if (json.has("fps")) { val f = json.getInt("fps"); cfg.setFps(f); applier.applyEncoder(cfg.getWidth(), cfg.getHeight(), cfg.getBitrate(), f) }
    if (json.has("bitrate")) { val b = json.getInt("bitrate"); cfg.setBitrate(b); applier.applyEncoder(cfg.getWidth(), cfg.getHeight(), b, cfg.getFps()) }
    if (json.has("width") && json.has("height")) { val w = json.getInt("width"); val h = json.getInt("height"); cfg.setResolution(w,h); applier.applyEncoder(w,h,cfg.getBitrate(), cfg.getFps()) }
    if (json.has("username") || json.has("password")) {
      // 只有当两个字段都存在时才更新认证信息，或者使用现有值作为默认值
      val u = if (json.has("username")) json.getString("username") else cfg.getUsername()
      val p = if (json.has("password")) json.getString("password") else cfg.getPassword()
      cfg.setCredentials(u, p)
      applier.applyCredentials(u, p)
    }
    if (json.has("deviceName")) { val n = json.getString("deviceName"); cfg.setDeviceName(n); applier.applyDeviceName(n) }
    if (json.has("showDeviceName")) { val s = json.getBoolean("showDeviceName"); cfg.setShowDeviceName(s); applier.applyShowDeviceName(s) }
    // 新增：处理编码器名称和MIME类型
    if (json.has("encoderName")) {
      val encName = json.optString("encoderName", "")
      cfg.setEncoderName(if (encName.isNotEmpty()) encName else null)
      applier.applyEncoder(cfg.getWidth(), cfg.getHeight(), cfg.getBitrate(), cfg.getFps())
    }
    if (json.has("mimeType")) { val mimeType = json.getString("mimeType"); cfg.setMimeType(mimeType); applier.applyEncoder(cfg.getWidth(), cfg.getHeight(), cfg.getBitrate(), cfg.getFps()) }
    return newFixedLengthResponse(Status.OK, "application/json", "{}")
  }

  private fun getConfig(session: IHTTPSession): Response {
    val ip = session.headers["host"] ?: "localhost"
    val host = ip.substringBefore(":")
    val json = JSONObject()
    json.put("username", cfg.getUsername())
    json.put("password", cfg.getPassword())
    json.put("port", cfg.getPort())
    json.put("bitrate", cfg.getBitrate())
    json.put("width", cfg.getWidth())
    json.put("height", cfg.getHeight())
    json.put("deviceName", cfg.getDeviceName())
    json.put("showDeviceName", cfg.isShowDeviceName())
    // 新增：返回编码器名称和MIME类型
    json.put("encoderName", cfg.getEncoderName())
    json.put("mimeType", cfg.getMimeType())
    json.put("fps", cfg.getFps())

    // 对用户名和密码进行URL编码以避免特殊字符问题
    val encodedUsername = java.net.URLEncoder.encode(cfg.getUsername(), "UTF-8")
    val encodedPassword = java.net.URLEncoder.encode(cfg.getPassword(), "UTF-8")
    json.put("rtsp", "rtsp://$encodedUsername:$encodedPassword@${host}:${cfg.getPort()}/live")
    json.put("web", "http://$ip/")
    return newFixedLengthResponse(Status.OK, "application/json", json.toString())
  }

  // 新增：获取设备支持的编码器列表
  private fun getEncoders(@Suppress("UNUSED_PARAMETER") session: IHTTPSession): Response {
    val encoders = com.qnvr.stream.EncoderManager.getSupportedEncoders()
    val jsonArray = org.json.JSONArray()
    
    encoders.forEach { encoder ->
      val jsonObj = org.json.JSONObject()
      jsonObj.put("name", encoder.name)
      jsonObj.put("mimeType", encoder.mimeType)
      jsonObj.put("isHardwareAccelerated", encoder.isHardwareAccelerated)
      jsonObj.put("displayName", encoder.getDisplayName())
      jsonArray.put(jsonObj)
    }
    
    return newFixedLengthResponse(Status.OK, "application/json", jsonArray.toString())
  }
}
