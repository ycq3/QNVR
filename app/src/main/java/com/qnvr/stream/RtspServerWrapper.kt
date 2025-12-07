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
import android.media.MediaFormat

class RtspServerWrapper(
    private val ctx: Context, 
    private val camera: CameraController, 
    private var port: Int, 
    private var username: String, 
    private var password: String, 
    width: Int, 
    height: Int, 
    fps: Int, 
    bitrate: Int, 
    private val encoderName: String? = null,  // 指定编码器名称
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC  // 编码格式
) {
  private var server: ServerSocket? = null
  private val running = AtomicBoolean(false)
  private lateinit var encoder: com.qnvr.stream.VideoEncoder  // 修复类引用
  private var width = width
  private var height = height
  private var fps = fps
  private var bitrate = bitrate
  private var serverIp: String = "0.0.0.0"

  fun start() {
    // Log all available encoders as requested by user
    logAvailableEncoders()

    try {
      android.util.Log.i("RtspServerWrapper", "Attempting to start RTSP server on port $port")
      server = ServerSocket(port, 50, java.net.InetAddress.getByName("0.0.0.0"))
      serverIp = server?.localSocketAddress?.toString()?.substringAfter("/")?.substringBefore(":") ?: "0.0.0.0"
      android.util.Log.i("RtspServerWrapper", "RTSP server started on port $port, IP: $serverIp")
      running.set(true)
      Thread { acceptLoop() }.start()
    } catch (e: Exception) {
      android.util.Log.e("RtspServerWrapper", "Failed to start RTSP server on port $port", e)
      Sentry.captureException(e)
      throw RuntimeException("Failed to start RTSP server on port $port", e)
    }

    try {
      val useSurface = !camera.isRtspWatermarkEnabled()
      android.util.Log.i("RtspServerWrapper", "Initializing video encoder with mimeType: $mimeType, encoderName: $encoderName, useSurface: $useSurface")
      encoder = com.qnvr.stream.VideoEncoder(width, height, fps, bitrate, encoderName, mimeType, useSurface)
      encoder.start()
      
      if (useSurface) {
          val surface = encoder.getInputSurface()
          if (surface != null) {
              camera.setEncoderSurface(surface)
          } else {
              android.util.Log.e("RtspServerWrapper", "Encoder input surface is null but useSurface is true")
          }
      } else {
          camera.setRtspEncoder(encoder)
      }
    } catch (e: Exception) {
      android.util.Log.e("RtspServerWrapper", "Failed to initialize video encoder", e)
      Sentry.captureException(e)
      // Don't stop the server if encoder fails, just log it.
      // The server will respond with 503 or 405 to clients.
      // stop() 
      // throw RuntimeException("Failed to initialize video encoder", e)
    }
  }

  private fun logAvailableEncoders() {
    val encoders = EncoderManager.getSupportedEncoders()
    android.util.Log.i("RtspServerWrapper", "=== Available Video Encoders ===")
    for (info in encoders) {
        android.util.Log.i("RtspServerWrapper", "Encoder: ${info.name}")
        android.util.Log.i("RtspServerWrapper", "  MIME: ${info.mimeType}")
        android.util.Log.i("RtspServerWrapper", "  Hardware: ${info.isHardwareAccelerated}")
        info.supportedWidths?.let { android.util.Log.i("RtspServerWrapper", "  Width: $it") }
        info.supportedHeights?.let { android.util.Log.i("RtspServerWrapper", "  Height: $it") }
        info.bitrateRange?.let { android.util.Log.i("RtspServerWrapper", "  Bitrate: $it") }
    }
    android.util.Log.i("RtspServerWrapper", "================================")
  }

  fun stop() {
    android.util.Log.i("RtspServerWrapper", "Stopping RTSP server")
    running.set(false)
    try { server?.close() } catch (_: Exception) {}
    if (::encoder.isInitialized) {
        encoder.stop()
    }
    camera.setRtspEncoder(null)
  }

  private fun acceptLoop() {
    android.util.Log.i("RtspServerWrapper", "RTSP server accept loop started")
    while (running.get()) {
      val s = try { 
        server?.accept() 
      } catch (e: Exception) { 
        if (running.get()) {
          android.util.Log.e("RtspServerWrapper", "Error accepting connection", e)
          Sentry.captureException(e)
        }
        null 
      } ?: continue
      
      android.util.Log.i("RtspServerWrapper", "New client connected from ${s.inetAddress.hostAddress}:${s.port}")
      Thread { handleClient(s) }.start()
    }
    android.util.Log.i("RtspServerWrapper", "RTSP server accept loop stopped")
  }

  private fun handleClient(socket: Socket) {
    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    val out = socket.getOutputStream()
    val clientIp = socket.inetAddress.hostAddress ?: "unknown"
    var sessionId = System.currentTimeMillis().toString()
    var cseq = 0
    var interleaved = 0
    var streaming = false
    
    // Check if encoder is ready
    var codecConfig: com.qnvr.stream.VideoEncoder.CodecConfig? = null
    if (this::encoder.isInitialized) {
        try {
            codecConfig = encoder.getCodecConfig()
        } catch (_: Exception) {}
    }

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

      if (!this::encoder.isInitialized) {
         writeRtsp(out, cseq, StatusLine.ServerError, emptyList())
         continue
      }

      when (method) {
        "OPTIONS" -> {
          writeRtsp(out, cseq, StatusLine.OK, listOf("Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY"))
        }
        "DESCRIBE" -> {
          // 确保SPS/PPS数据已准备好 (仅针对需要SPS/PPS的编码格式)
          val needsSpsPps = mimeType == MediaFormat.MIMETYPE_VIDEO_AVC || mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC
          
          var attempts = 0
          while (codecConfig == null && needsSpsPps && attempts < 30) {
            codecConfig = encoder.getCodecConfig()
            if (codecConfig == null) {
              Thread.sleep(100)
              attempts++
            }
          }
          
          // 如果需要SPS/PPS但仍然没有数据，返回错误
          if (codecConfig == null && needsSpsPps) {
            writeRtsp(out, cseq, StatusLine.NotAllowed, emptyList())
            continue
          }
          
          val sdp = buildSdp(codecConfig)
          writeRtsp(out, cseq, StatusLine.OK, listOf("Content-Base: rtsp://$clientIp:${port}/live/", "Content-Type: application/sdp"), sdp)
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
    val sender = com.qnvr.stream.RtpStreamSender(out, mimeType)  // Pass mimeType
    while (true) {
      try {
        val frame = encoder.poll(50000) ?: continue // Wait up to 50ms
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

  private enum class StatusLine(val code: Int, val text: String) { OK(200, "OK"), NotAllowed(405, "Method Not Allowed"), Unauthorized(401, "Unauthorized"), ServerError(500, "Internal Server Error") }

  private fun buildSdp(config: com.qnvr.stream.VideoEncoder.CodecConfig?): String {
    val sps = config?.sps
    val pps = config?.pps
    val vps = config?.vps
    
    val spsB64 = if (sps != null) android.util.Base64.encodeToString(sps, android.util.Base64.NO_WRAP) else ""
    val ppsB64 = if (pps != null) android.util.Base64.encodeToString(pps, android.util.Base64.NO_WRAP) else ""
    val vpsB64 = if (vps != null) android.util.Base64.encodeToString(vps, android.util.Base64.NO_WRAP) else ""
    
    // 根据MIME类型设置SDP参数
    val (rtpmap, fmtp) = when (mimeType) {
      MediaFormat.MIMETYPE_VIDEO_HEVC -> {
        val rtpmap = "a=rtpmap:96 H265/90000\r\n"
        val fmtp = "a=fmtp:96 sprop-vps=$vpsB64;sprop-sps=$spsB64;sprop-pps=$ppsB64\r\n"
        Pair(rtpmap, fmtp)
      }
      MediaFormat.MIMETYPE_VIDEO_VP8 -> {
        val rtpmap = "a=rtpmap:96 VP8/90000\r\n"
        val fmtp = "a=fmtp:96\r\n"
        Pair(rtpmap, fmtp)
      }
      MediaFormat.MIMETYPE_VIDEO_VP9 -> {
        val rtpmap = "a=rtpmap:96 VP9/90000\r\n"
        val fmtp = "a=fmtp:96\r\n"
        Pair(rtpmap, fmtp)
      }
      else -> {  // 默认H.264/AVC
        val rtpmap = "a=rtpmap:96 H264/90000\r\n"
        val fmtp = "a=fmtp:96 packetization-mode=1;profile-level-id=42e01e;sprop-parameter-sets=$spsB64,$ppsB64\r\n"
        Pair(rtpmap, fmtp)
      }
    }
    
    return "v=0\r\n" +
      "o=- 0 0 IN IP4 127.0.0.1\r\n" +
      "s=QNVR\r\n" +
      "c=IN IP4 0.0.0.0\r\n" +
      "t=0 0\r\n" +
      "m=video 0 RTP/AVP 96\r\n" +
      "a=control:trackID=0\r\n" +
      rtpmap +
      fmtp
  }

  private fun splitAnnexB(data: ByteArray): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    var i = 0
    while (i + 2 < data.size) {
      // Check for 00 00 00 01 (4 bytes)
      if (i + 3 < data.size && data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
        val start = i + 4
        var j = start
        while (j + 2 < data.size) {
          if (j + 3 < data.size && data[j].toInt() == 0 && data[j + 1].toInt() == 0 && data[j + 2].toInt() == 0 && data[j + 3].toInt() == 1) break
          if (data[j].toInt() == 0 && data[j + 1].toInt() == 0 && data[j + 2].toInt() == 1) break
          j++
        }
        val end = if (j + 2 < data.size) j else data.size
        val nal = data.copyOfRange(start, end)
        out.add(nal)
        i = j
      } 
      // Check for 00 00 01 (3 bytes)
      else if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
        val start = i + 3
        var j = start
        while (j + 2 < data.size) {
          if (j + 3 < data.size && data[j].toInt() == 0 && data[j + 1].toInt() == 0 && data[j + 2].toInt() == 0 && data[j + 3].toInt() == 1) break
          if (data[j].toInt() == 0 && data[j + 1].toInt() == 0 && data[j + 2].toInt() == 1) break
          j++
        }
        val end = if (j + 2 < data.size) j else data.size
        val nal = data.copyOfRange(start, end)
        out.add(nal)
        i = j
      } else {
        i++
      }
    }
    return out
  }

  fun updateEncoder(w: Int, h: Int, f: Int, b: Int, encName: String? = null, mime: String = MediaFormat.MIMETYPE_VIDEO_AVC) {
    width = w; height = h; fps = f; bitrate = b
    try { encoder.stop() } catch (_: Exception) {}
    
    // Update camera FPS to match requested FPS
    camera.setFps(fps)
    
    val useSurface = !camera.isRtspWatermarkEnabled()
    encoder = com.qnvr.stream.VideoEncoder(width, height, fps, bitrate, encName, mime, useSurface)
    encoder.start()
    
    if (useSurface) {
        val surface = encoder.getInputSurface()
        if (surface != null) {
            camera.setEncoderSurface(surface)
        } else {
             android.util.Log.e("RtspServerWrapper", "Encoder input surface is null but useSurface is true")
        }
    } else {
        camera.setRtspEncoder(encoder)
    }
  }

  fun updateCredentials(u: String, p: String) { username = u; password = p }

  private fun checkAuth(headers: Map<String, String>): Boolean {
    // 如果用户名和密码都为空，则不需要验证
    if (username.isEmpty() && password.isEmpty()) return true
    // 如果没有设置密码，则允许访问
    if (password.isEmpty()) return true
    // 如果设置了密码但没有提供认证头，则需要验证
    val auth = headers["Authorization"] ?: return false
    if (!auth.startsWith("Basic ")) return false
    val b64 = auth.substring(6)
    val decoded = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
    // 调试日志，输出解码后的认证信息
    println("Received auth: $decoded, expected: $username:$password")
    
    // 处理URL编码的用户名和密码
    return try {
      val parts = decoded.split(":", limit = 2)
      if (parts.size != 2) return false
      
      val receivedUsername = java.net.URLDecoder.decode(parts[0], "UTF-8")
      val receivedPassword = java.net.URLDecoder.decode(parts[1], "UTF-8")
      
      receivedUsername == username && receivedPassword == password
    } catch (e: Exception) {
      // 如果解码失败，回退到原始比较
      decoded == "$username:$password"
    }
  }
}
