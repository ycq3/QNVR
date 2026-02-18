package com.qnvr.stream

import android.content.Context
import com.qnvr.camera.CameraController
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.sentry.Sentry
import android.media.MediaFormat
import android.Manifest
import androidx.core.content.ContextCompat

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
    private val encoderName: String? = null,
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
) {
  private var server: ServerSocket? = null
  private val running = AtomicBoolean(false)
  private lateinit var encoder: com.qnvr.stream.VideoEncoder
  private lateinit var audioEncoder: com.qnvr.stream.AudioEncoder
  private var audioEnabled = false
  private var width = width
  private var height = height
  private var fps = fps
  private var bitrate = bitrate
  private var serverIp: String = "0.0.0.0"
  private val clientCount = AtomicInteger(0)
  private var lowPowerMode = false
  private var originalFps = fps
  private var originalBitrate = bitrate
  private var pushClient: RtspPushClient? = null
  private var pushEnabled = false
  private var pushUrl: String? = null

  fun start() {
    logAvailableEncoders()

    var attempts = 0
    val maxAttempts = 10
    var lastException: Throwable? = null
    
    while (attempts < maxAttempts) {
      try {
        android.util.Log.i("RtspServerWrapper", "Attempting to start RTSP server on port $port (attempt ${attempts + 1}/$maxAttempts)")
        server = ServerSocket()
        server?.reuseAddress = true
        server?.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), port), 50)
        serverIp = server?.localSocketAddress?.toString()?.substringAfter("/")?.substringBefore(":") ?: "0.0.0.0"
        android.util.Log.i("RtspServerWrapper", "RTSP server started on port $port, IP: $serverIp")
        running.set(true)
        Thread { acceptLoop() }.start()
        break
      } catch (e: Exception) {
        lastException = e
        android.util.Log.w("RtspServerWrapper", "Failed to start on port $port: ${e.message}")
        attempts++
        if (attempts < maxAttempts) {
          port++
          android.util.Log.i("RtspServerWrapper", "Trying next port: $port")
        }
      }
    }
    
    if (!running.get()) {
      val errorMsg = "Failed to start RTSP server after $maxAttempts attempts. Last error: ${lastException?.message}"
      android.util.Log.e("RtspServerWrapper", errorMsg, lastException)
      lastException?.let { Sentry.captureException(it) }
      throw RuntimeException(errorMsg, lastException)
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
      
      Thread.sleep(100)
      val actualEncoderName = encoder.getSelectedEncoderName() ?: encoderName ?: "未知"
      android.util.Log.i("RtspServerWrapper", "Setting encoder info for stats monitor: $actualEncoderName, ${width}x${height}, $bitrate")
      camera.getStatsMonitor()?.setEncoderInfo(actualEncoderName, mimeType, width, height, bitrate)
    } catch (e: Exception) {
      android.util.Log.e("RtspServerWrapper", "Failed to initialize video encoder", e)
      Sentry.captureException(e)
    }
    val audioPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (audioPerm) {
      try {
        audioEncoder = com.qnvr.stream.AudioEncoder()
        audioEncoder.start()
        audioEnabled = true
      } catch (e: Exception) {
        android.util.Log.e("RtspServerWrapper", "Failed to initialize audio encoder", e)
        Sentry.captureException(e)
        audioEnabled = false
      }
    } else {
      android.util.Log.w("RtspServerWrapper", "Audio permission missing; audio disabled")
      audioEnabled = false
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
    stopPushInternal()
    if (::encoder.isInitialized) {
        encoder.stop()
    }
    if (::audioEncoder.isInitialized) {
        audioEncoder.stop()
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
      clientCount.incrementAndGet()
      checkPowerMode()
      
      Thread { 
        try {
          handleClient(s)
        } finally {
          clientCount.decrementAndGet()
          checkPowerMode()
        }
      }.start()
    }
    android.util.Log.i("RtspServerWrapper", "RTSP server accept loop stopped")
  }
  
  private fun checkPowerMode() {
    val currentClients = clientCount.get()
    android.util.Log.i("RtspServerWrapper", "Current client count: $currentClients")
    
    if (currentClients == 0 && !lowPowerMode) {
      enterLowPowerMode()
    } else if (currentClients > 0 && lowPowerMode) {
      exitLowPowerMode()
    }
  }
  
  private fun enterLowPowerMode() {
    android.util.Log.i("RtspServerWrapper", "Entering low power mode - no clients connected")
    lowPowerMode = true
  }
  
  private fun exitLowPowerMode() {
    android.util.Log.i("RtspServerWrapper", "Exiting low power mode - client connected")
    lowPowerMode = false
  }

  private fun handleClient(socket: Socket) {
    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    val out = socket.getOutputStream()
    val localIp = socket.localAddress.hostAddress ?: "127.0.0.1"
    var sessionId = System.currentTimeMillis().toString()
    var cseq = 0
    var videoChannel = 0
    var audioChannel = 2
    var audioSetup = false
    var streaming = false
    
    android.util.Log.i("RtspServerWrapper", "handleClient started - audioEnabled: $audioEnabled, audioEncoder initialized: ${this::audioEncoder.isInitialized}")
    
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
          // Wait up to 3 seconds for SPS/PPS
          while (codecConfig == null && needsSpsPps && attempts < 30) {
            try {
                codecConfig = encoder.getCodecConfig()
            } catch (_: Exception) {}
            if (codecConfig == null) {
              Thread.sleep(100)
              attempts++
            }
          }
          
          // 如果仍然没有SPS/PPS，记录警告但允许连接 (某些播放器支持带内参数，或者参数会在流中发送)
          if (codecConfig == null && needsSpsPps) {
             android.util.Log.w("RtspServerWrapper", "SPS/PPS not available after wait, proceeding with default SDP")
          }
          
          var audioConfig: com.qnvr.stream.AudioEncoder.AudioConfig? = null
          if (audioEnabled && this::audioEncoder.isInitialized) {
            var aAttempts = 0
            while (audioConfig == null && aAttempts < 30) {
              try {
                audioConfig = audioEncoder.getAudioConfig()
              } catch (_: Exception) {}
              if (audioConfig == null) {
                Thread.sleep(100)
                aAttempts++
              }
            }
          }
          val sdp = buildSdp(codecConfig, audioConfig, localIp)
          writeRtsp(out, cseq, StatusLine.OK, listOf("Content-Base: rtsp://$localIp:${port}/live/", "Content-Type: application/sdp"), sdp)
        }
        "SETUP" -> {
          val transport = headers["Transport"] ?: ""
          android.util.Log.i("RtspServerWrapper", "SETUP request - Transport: $transport, URL: ${parts.getOrNull(1)}")
          
          val inter = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
          val isTcp = transport.contains("RTP/AVP/TCP", ignoreCase = true) || inter != null
          
          if (!isTcp) {
            android.util.Log.w("RtspServerWrapper", "Client requested UDP transport, returning 461 Unsupported Transport")
            writeRtsp(out, cseq, StatusLine.UnsupportedTransport, emptyList())
            continue
          }
          
          val interleaved = inter?.groupValues?.get(1)?.toIntOrNull() ?: 0
          val trackId = getTrackId(parts.getOrNull(1))
          
          android.util.Log.i("RtspServerWrapper", "SETUP - trackId: $trackId, interleaved: $interleaved")
          
          if (trackId == 1) {
            audioChannel = interleaved
            audioSetup = true
          } else {
            videoChannel = interleaved
          }
          
          val responseTransport = "Transport: RTP/AVP/TCP;unicast;interleaved=$interleaved-${interleaved+1}"
          android.util.Log.i("RtspServerWrapper", "SETUP response - $responseTransport")
          writeRtsp(out, cseq, StatusLine.OK, listOf(responseTransport, "Session: $sessionId"))
        }
        "PLAY" -> {
          writeRtsp(out, cseq, StatusLine.OK, listOf("Range: npt=0-", "Session: $sessionId"))
          streaming = true
          
          val queue = java.util.concurrent.LinkedBlockingQueue<com.qnvr.stream.VideoEncoder.EncodedFrame>(60)
          val callback = object : com.qnvr.stream.VideoEncoder.FrameCallback {
              override fun onFrame(frame: com.qnvr.stream.VideoEncoder.EncodedFrame) {
                  queue.offer(frame)
              }
          }

          val audioQueue = java.util.concurrent.LinkedBlockingQueue<com.qnvr.stream.AudioEncoder.EncodedAudioFrame>(120)
          val audioCallback = object : com.qnvr.stream.AudioEncoder.FrameCallback {
              override fun onFrame(frame: com.qnvr.stream.AudioEncoder.EncodedAudioFrame) {
                  audioQueue.offer(frame)
              }
          }
          
          if (this::encoder.isInitialized) {
              encoder.addCallback(callback)
          }
          android.util.Log.i("RtspServerWrapper", "PLAY - audioEnabled: $audioEnabled, audioSetup: $audioSetup, audioChannel: $audioChannel")
          if (audioEnabled && this::audioEncoder.isInitialized && audioSetup) {
              audioEncoder.addCallback(audioCallback)
              android.util.Log.i("RtspServerWrapper", "Audio callback registered, starting audio thread on channel $audioChannel")
          }
          
          val audioThread = if (audioEnabled && this::audioEncoder.isInitialized && audioSetup) {
              Thread { audioStreamLoop(out, audioChannel, audioQueue, audioEncoder.getAudioConfig()) }.apply { start() }
          } else null

          try {
              streamLoop(out, videoChannel, queue)
          } finally {
              if (this::encoder.isInitialized) {
                  encoder.removeCallback(callback)
              }
              if (audioEnabled && this::audioEncoder.isInitialized) {
                  audioEncoder.removeCallback(audioCallback)
              }
              if (audioThread != null) {
                  try { audioThread.interrupt() } catch (_: Exception) {}
              }
          }
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

  private fun streamLoop(out: OutputStream, channel: Int, queue: java.util.concurrent.LinkedBlockingQueue<com.qnvr.stream.VideoEncoder.EncodedFrame>) {
    val sender = com.qnvr.stream.RtpStreamSender(out, mimeType)
    
    if (this::encoder.isInitialized) {
        // Request a key frame immediately for fast startup
        encoder.requestKeyFrame()
        
        // Send initial configuration (SPS/PPS/VPS)
        val config = encoder.getCodecConfig()
        if (config != null) {
            val ts = 0
            if (config.vps != null) sender.sendNal(config.vps, ts, channel)
            sender.sendNal(config.sps, ts, channel)
            sender.sendNal(config.pps, ts, channel)
        }
    }
    
    while (true) {
      try {
        val frame = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
        
        val ts90k = ((frame.timeUs / 1000L) * 90L).toInt()
        
        // Always prepend SPS/PPS before keyframes to ensure decoding capability
        // This handles cases where the initial config was missed or dropped
        if (frame.keyframe && this::encoder.isInitialized) {
             val config = encoder.getCodecConfig()
             if (config != null) {
                 if (config.vps != null) sender.sendNal(config.vps, ts90k, channel)
                 sender.sendNal(config.sps, ts90k, channel)
                 sender.sendNal(config.pps, ts90k, channel)
             }
        }
        
        val nals = splitAnnexB(frame.data)
        for (nal in nals) {
            if (nal.isNotEmpty()) {
                sender.sendNal(nal, ts90k, channel)
            }
        }
      } catch (e: Exception) {
        // Socket closed or error
        break
      }
    }
  }

  private fun audioStreamLoop(out: OutputStream, channel: Int, queue: java.util.concurrent.LinkedBlockingQueue<com.qnvr.stream.AudioEncoder.EncodedAudioFrame>, config: com.qnvr.stream.AudioEncoder.AudioConfig?) {
    val sender = com.qnvr.stream.RtpAudioSender(out)
    val sampleRate = config?.sampleRate ?: 44100
    android.util.Log.i("RtspServerWrapper", "audioStreamLoop started - channel: $channel, sampleRate: $sampleRate")
    var frameCount = 0
    while (true) {
      if (Thread.currentThread().isInterrupted) break
      try {
        val frame = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
        val ts = ((frame.timeUs * sampleRate) / 1_000_000L).toInt()
        sender.sendAacFrame(frame.data, ts, channel)
        frameCount++
        if (frameCount % 100 == 0) {
          android.util.Log.i("RtspServerWrapper", "Audio frames sent: $frameCount")
        }
      } catch (e: Exception) {
        android.util.Log.e("RtspServerWrapper", "audioStreamLoop error: ${e.message}")
        break
      }
    }
    android.util.Log.i("RtspServerWrapper", "audioStreamLoop ended - total frames: $frameCount")
  }

  private fun getTrackId(url: String?): Int? {
    if (url == null) return null
    val idx = url.indexOf("trackID=")
    if (idx < 0) return null
    return url.substring(idx + 8).toIntOrNull()
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

  private enum class StatusLine(val code: Int, val text: String) { OK(200, "OK"), NotAllowed(405, "Method Not Allowed"), Unauthorized(401, "Unauthorized"), ServerError(500, "Internal Server Error"), UnsupportedTransport(461, "Unsupported Transport") }

  private fun buildSdp(config: com.qnvr.stream.VideoEncoder.CodecConfig?, audioConfig: com.qnvr.stream.AudioEncoder.AudioConfig?, serverIp: String): String {
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
        val sb = StringBuilder("a=fmtp:96")
        if (vpsB64.isNotEmpty()) sb.append(" sprop-vps=$vpsB64;")
        if (spsB64.isNotEmpty()) sb.append(" sprop-sps=$spsB64;")
        if (ppsB64.isNotEmpty()) sb.append(" sprop-pps=$ppsB64;")
        if (sb.endsWith(";")) sb.setLength(sb.length - 1)
        sb.append("\r\n")
        Pair(rtpmap, sb.toString())
      }
      else -> {
        val rtpmap = "a=rtpmap:96 H264/90000\r\n"
        val profileLevelId = if (sps != null && sps.size >= 4) {
             val profile = sps[1]
             val constraints = sps[2]
             val level = sps[3]
             String.format("%02x%02x%02x", profile, constraints, level)
        } else {
             "42e01e"
        }
        val sprop = if (spsB64.isNotEmpty() && ppsB64.isNotEmpty()) ";sprop-parameter-sets=$spsB64,$ppsB64" else ""
        val fmtp = "a=fmtp:96 packetization-mode=1;profile-level-id=$profileLevelId$sprop\r\n"
        Pair(rtpmap, fmtp)
      }
    }
    
    val audioSdp = if (audioConfig != null) {
      val asc = android.util.Base64.encodeToString(audioConfig.audioSpecificConfig, android.util.Base64.NO_WRAP)
      val audioFmtp = "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;config=$asc;SizeLength=13;IndexLength=3;IndexDeltaLength=3\r\n"
      "m=audio 0 RTP/AVP 97\r\n" +
        "a=rtpmap:97 MPEG4-GENERIC/${audioConfig.sampleRate}/${audioConfig.channelCount}\r\n" +
        audioFmtp +
        "a=control:trackID=1\r\n"
    } else ""
    
    return "v=0\r\n" +
      "o=- 0 0 IN IP4 $serverIp\r\n" +
      "s=QNVR\r\n" +
      "c=IN IP4 0.0.0.0\r\n" +
      "t=0 0\r\n" +
      "m=video 0 RTP/AVP 96\r\n" +
      "a=control:trackID=0\r\n" +
      rtpmap +
      fmtp +
      audioSdp
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
    
    Thread.sleep(100)
    val actualEncoderName = encoder.getSelectedEncoderName() ?: encName ?: "未知"
    camera.getStatsMonitor()?.setEncoderInfo(actualEncoderName, mime, width, height, bitrate)
    restartPush()
  }

  fun updateCredentials(u: String, p: String) { username = u; password = p }

  fun getActualEncoderName(): String? {
    return if (::encoder.isInitialized) {
      encoder.getSelectedEncoderName()
    } else {
      null
    }
  }

  fun getActualPort(): Int = port

  fun updatePushConfig(enabled: Boolean, url: String?) {
    pushEnabled = enabled
    pushUrl = url
    restartPush()
  }

  private fun restartPush() {
    stopPushInternal()
    val targetUrl = pushUrl
    if (pushEnabled && !targetUrl.isNullOrBlank() && this::encoder.isInitialized) {
      val audio = if (audioEnabled && this::audioEncoder.isInitialized) audioEncoder else null
      pushClient = RtspPushClient(targetUrl, encoder, audio, mimeType, audioEnabled)
      pushClient?.start()
    }
  }

  private fun stopPushInternal() {
    try { pushClient?.stop() } catch (_: Exception) {}
    pushClient = null
  }

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
    android.util.Log.d("RtspServerWrapper", "Received auth: $decoded, expected: $username:$password")
    
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
