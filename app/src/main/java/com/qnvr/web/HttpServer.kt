package com.qnvr.web

import android.content.Context
import com.qnvr.camera.CameraController
import com.qnvr.preview.MjpegStreamer
import com.qnvr.config.ConfigStore
import com.qnvr.config.ConfigApplier
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
    if (uri == "/api/config" && session.method == Method.GET) return getConfig(session)
    if (uri == "/api/config" && session.method == Method.POST) return handleConfig(session)
    return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404")
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
    val rtsp = "rtsp://${cfg.getUsername()}:${cfg.getPassword()}@${host}:${cfg.getPort()}/live"
    val json = JSONObject()
    json.put("rtsp", rtsp)
    json.put("web", "http://$ip/")
    json.put("deviceName", cfg.getDeviceName())
    json.put("showDeviceName", cfg.isShowDeviceName())
    json.put("bitrate", cfg.getBitrate())
    json.put("width", cfg.getWidth())
    json.put("height", cfg.getHeight())
    json.put("port", cfg.getPort())
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
    if (json.has("bitrate")) { val b = json.getInt("bitrate"); cfg.setBitrate(b); applier.applyEncoder(cfg.getWidth(), cfg.getHeight(), b) }
    if (json.has("width") && json.has("height")) { val w = json.getInt("width"); val h = json.getInt("height"); cfg.setResolution(w,h); applier.applyEncoder(w,h,cfg.getBitrate()) }
    if (json.has("username") || json.has("password")) { val u = json.optString("username", cfg.getUsername()); val p = json.optString("password", cfg.getPassword()); cfg.setCredentials(u,p); applier.applyCredentials(u,p) }
    if (json.has("deviceName")) { val n = json.getString("deviceName"); cfg.setDeviceName(n); applier.applyDeviceName(n) }
    if (json.has("showDeviceName")) { val s = json.getBoolean("showDeviceName"); cfg.setShowDeviceName(s); applier.applyShowDeviceName(s) }
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
    json.put("rtsp", "rtsp://${cfg.getUsername()}:${cfg.getPassword()}@${host}:${cfg.getPort()}/live")
    json.put("web", "http://$ip/")
    return newFixedLengthResponse(Status.OK, "application/json", json.toString())
  }
}
