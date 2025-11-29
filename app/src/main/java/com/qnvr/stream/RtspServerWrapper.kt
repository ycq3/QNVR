package com.qnvr.stream

import android.content.Context
import com.qnvr.camera.CameraController
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import io.sentry.Sentry

class RtspServerWrapper(private val ctx: Context, private val camera: CameraController, private var port: Int, private var username: String, private var password: String, width: Int, height: Int, fps: Int, bitrate: Int) {
  private var server: ServerSocket? = null
  private val running = AtomicBoolean(false)
  private lateinit var encoder: H264Encoder
  private var width = width
  private var height = height
  private var fps = fps
  private var bitrate = bitrate

  fun start() {
    encoder = H264Encoder(width, height, fps, bitrate)
    encoder.start()
    camera.setEncoderSurface(encoder.getInputSurface())
    server = ServerSocket(port)
    running.set(true)
    Thread { acceptLoop() }.start()
  }

  fun stop() {
    running.set(false)
    try { server?.close() } catch (_: Exception) {}
    encoder.stop()
  }

  private fun acceptLoop() {
    while (running.get()) {
      val s = try { server?.accept() } catch (e: Exception) { null } ?: continue
      Thread { handleClient(s) }.start()
    }
  }

  private fun handleClient(socket: Socket) {
    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    val out = socket.getOutputStream()
    var sessionId = System.currentTimeMillis().toString()
    var cseq = 0
    var interleaved = 0
    var streaming = false
    var spspps = encoder.getSpsPps()
    while (true) {
      val reqLine = reader.readLine() ?: break
      val parts = reqLine.split(" ")
      val method = parts.getOrNull(0) ?: break
      val headers = mutableMapOf<String, String>()
      var line: String
      while (true) {
        line = reader.readLine() ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(":")
        if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
      }
      cseq = headers["CSeq"]?.toIntOrNull() ?: (cseq + 1)
      if (!checkAuth(headers)) {
        writeRtsp(out, cseq, StatusLine.Unauthorized, listOf("WWW-Authenticate: Basic realm=\"QNVR\""))
        continue
      }
      when (method) {
        "OPTIONS" -> {
          writeRtsp(out, cseq, StatusLine.OK, listOf("Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY"))
        }
        "DESCRIBE" -> {
          if (spspps == null) spspps = encoder.getSpsPps()
          val sdp = buildSdp(spspps?.first, spspps?.second)
          writeRtsp(out, cseq, StatusLine.OK, listOf("Content-Base: rtsp://0.0.0.0:${port}/live/", "Content-Type: application/sdp"), sdp)
        }
        "SETUP" -> {
          val transport = headers["Transport"] ?: ""
          val inter = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
          interleaved = inter?.groupValues?.get(1)?.toIntOrNull() ?: 0
          writeRtsp(out, cseq, StatusLine.OK, listOf("Transport: RTP/AVP/TCP;unicast;interleaved=$interleaved-${interleaved+1}", "Session: $sessionId"))
        }
        "PLAY" -> {
          writeRtsp(out, cseq, StatusLine.OK, listOf("Range: npt=0-", "Session: $sessionId"))
          streaming = true
          streamLoop(out, interleaved)
        }
        "TEARDOWN" -> {
          writeRtsp(out, cseq, StatusLine.OK, listOf("Session: $sessionId"))
          break
        }
        else -> {
          writeRtsp(out, cseq, StatusLine.NotAllowed, emptyList())
        }
      }
      if (!streaming) continue
    }
    try { socket.close() } catch (_: Exception) {}
  }

  private fun streamLoop(out: OutputStream, channel: Int) {
    val sender = RtpH264Sender(out)
    while (true) {
      try {
        val frame = encoder.poll() ?: continue
        val nals = splitAnnexB(frame.data)
        val ts90k = ((frame.timeUs / 1000L) * 90L).toInt()
        for (nal in nals) sender.sendNal(nal, ts90k, channel)
      } catch (e: Exception) {
        Sentry.captureException(e)
        break
      }
    }
  }

  private fun writeRtsp(out: OutputStream, cseq: Int, status: StatusLine, headers: List<String>, body: String = "") {
    val sb = StringBuilder()
    sb.append("RTSP/1.0 ").append(status.code).append(" ").append(status.text).append("\r\n")
    sb.append("CSeq: ").append(cseq).append("\r\n")
    headers.forEach { sb.append(it).append("\r\n") }
    if (body.isNotEmpty()) {
      sb.append("Content-Length: ").append(body.toByteArray().size).append("\r\n")
    }
    sb.append("\r\n")
    out.write(sb.toString().toByteArray())
    if (body.isNotEmpty()) out.write(body.toByteArray())
    out.flush()
  }

  private enum class StatusLine(val code: Int, val text: String) { OK(200, "OK"), NotAllowed(405, "Method Not Allowed"), Unauthorized(401, "Unauthorized") }

  private fun buildSdp(sps: ByteArray?, pps: ByteArray?): String {
    val spsB64 = if (sps != null) android.util.Base64.encodeToString(sps, android.util.Base64.NO_WRAP) else ""
    val ppsB64 = if (pps != null) android.util.Base64.encodeToString(pps, android.util.Base64.NO_WRAP) else ""
    return "v=0\r\n" +
      "o=- 0 0 IN IP4 0.0.0.0\r\n" +
      "s=QNVR\r\n" +
      "c=IN IP4 0.0.0.0\r\n" +
      "t=0 0\r\n" +
      "m=video 0 RTP/AVP 96\r\n" +
      "a=control:trackID=0\r\n" +
      "a=rtpmap:96 H264/90000\r\n" +
      "a=fmtp:96 packetization-mode=1;profile-level-id=42e01e;sprop-parameter-sets=$spsB64,$ppsB64\r\n"
  }

  private fun splitAnnexB(data: ByteArray): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    var i = 0
    while (i + 4 < data.size) {
      if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
        val start = i + 4
        var j = start
        while (j + 4 < data.size && !(data[j].toInt() == 0 && data[j + 1].toInt() == 0 && data[j + 2].toInt() == 0 && data[j + 3].toInt() == 1)) j++
        val nal = data.copyOfRange(start, if (j + 4 < data.size) j else data.size)
        out.add(nal)
        i = j
      } else i++
    }
    return out
  }

  fun updateEncoder(w: Int, h: Int, f: Int, b: Int) {
    width = w; height = h; fps = f; bitrate = b
    try { encoder.stop() } catch (_: Exception) {}
    encoder = H264Encoder(width, height, fps, bitrate)
    encoder.start()
    camera.setEncoderSurface(encoder.getInputSurface())
  }

  fun updateCredentials(u: String, p: String) { username = u; password = p }

  private fun checkAuth(headers: Map<String, String>): Boolean {
    if (password.isEmpty()) return true
    val auth = headers["Authorization"] ?: return false
    if (!auth.startsWith("Basic ")) return false
    val b64 = auth.substring(6)
    val decoded = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
    return decoded == "$username:$password"
  }
}
