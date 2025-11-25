package com.qnvr.web

import android.content.Context
import com.qnvr.camera.CameraController
import com.qnvr.preview.MjpegStreamer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONObject
import java.util.HashMap

class HttpServer(ctx: Context, private val camera: CameraController, private val mjpeg: MjpegStreamer) : NanoHTTPD(8080) {
  private val assets = ctx.assets
  fun begin() { super.start(5000) }
  fun shutdown() { super.stop() }

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri
    if (uri == "/") return serveAssetHtml("web/index.html")
    if (uri == "/stream.mjpg") return serveMjpeg()
    if (uri == "/api/status") return serveStatus(session)
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
        val img = mjpeg.nextFrame() ?: continue
        val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${img.size}\r\n\r\n"
        pos.write(header.toByteArray())
        pos.write(img)
        pos.write("\r\n".toByteArray())
        pos.flush()
        Thread.sleep(100)
      }
    }.start()
    val res = newChunkedResponse(Status.OK, "multipart/x-mixed-replace; boundary=$boundary", pis)
    res.addHeader("Connection", "close")
    res.setChunkedTransfer(true)
    return res
  }

  private fun serveStatus(session: IHTTPSession): Response {
    val ip = session.headers["host"] ?: "localhost"
    val rtsp = camera.getRtspSuggestedUrl(ip.substringBefore(":"))
    val json = JSONObject()
    json.put("rtsp", rtsp)
    json.put("web", "http://$ip/")
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
    return newFixedLengthResponse(Status.OK, "application/json", "{}")
  }
}
