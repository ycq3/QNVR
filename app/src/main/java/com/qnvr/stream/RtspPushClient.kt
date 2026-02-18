package com.qnvr.stream

import android.media.MediaFormat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class RtspPushClient(
    private val url: String,
    private val videoEncoder: VideoEncoder,
    private val audioEncoder: AudioEncoder?,
    private val mimeType: String,
    private val enableAudio: Boolean
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var out: OutputStream? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread { runLoop() }.apply { start() }
    }

    fun stop() {
        running.set(false)
        try { thread?.interrupt() } catch (_: Exception) {}
        closeConnection()
    }

    private fun runLoop() {
        var delay = 1000L
        while (running.get()) {
            try {
                connectAndStream()
                delay = 1000L
            } catch (_: Exception) {
                closeConnection()
                try { Thread.sleep(delay) } catch (_: Exception) {}
                delay = min(delay * 2, 10000L)
            }
        }
        closeConnection()
    }

    private fun connectAndStream() {
        val uri = URI(url)
        val host = uri.host ?: throw IllegalArgumentException("Invalid RTSP URL")
        val port = if (uri.port > 0) uri.port else 554
        val path = if (!uri.rawPath.isNullOrEmpty()) uri.rawPath else "/live"
        val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
        val requestUrl = "rtsp://$host:$port$path$query"
        val authHeader = buildAuthHeader(uri)

        socket = Socket(host, port)
        out = socket?.getOutputStream()
        reader = BufferedReader(InputStreamReader(socket?.getInputStream()))

        val videoConfig = waitForVideoConfig()
        val audioConfig = if (enableAudio) waitForAudioConfig() else null
        val sdp = buildSdp(videoConfig, audioConfig, host)

        var cseq = 1
        sendRequest("ANNOUNCE", requestUrl, authHeader, cseq++, sdp)
        var sessionId = sendSetup(requestUrl, authHeader, cseq++, "trackID=0", "0-1", null)
        if (audioConfig != null) {
            sessionId = sendSetup(requestUrl, authHeader, cseq++, "trackID=1", "2-3", sessionId)
        }
        sendRequest("RECORD", requestUrl, authHeader, cseq++, null, sessionId)

        val videoQueue = LinkedBlockingQueue<VideoEncoder.EncodedFrame>(60)
        val videoCallback = object : VideoEncoder.FrameCallback {
            override fun onFrame(frame: VideoEncoder.EncodedFrame) {
                videoQueue.offer(frame)
            }
        }

        val audioQueue = LinkedBlockingQueue<AudioEncoder.EncodedAudioFrame>(120)
        val audioCallback = object : AudioEncoder.FrameCallback {
            override fun onFrame(frame: AudioEncoder.EncodedAudioFrame) {
                audioQueue.offer(frame)
            }
        }

        videoEncoder.addCallback(videoCallback)
        if (audioConfig != null && audioEncoder != null) {
            audioEncoder.addCallback(audioCallback)
        }

        val audioThread = if (audioConfig != null && audioEncoder != null) {
            Thread { audioLoop(audioQueue, audioConfig) }.apply { start() }
        } else null

        try {
            videoLoop(videoQueue)
        } finally {
            try { videoEncoder.removeCallback(videoCallback) } catch (_: Exception) {}
            if (audioEncoder != null) {
                try { audioEncoder.removeCallback(audioCallback) } catch (_: Exception) {}
            }
            if (audioThread != null) {
                try { audioThread.interrupt() } catch (_: Exception) {}
            }
        }
    }

    private fun sendSetup(baseUrl: String, authHeader: String?, cseq: Int, track: String, interleaved: String, session: String?): String? {
        val url = if (baseUrl.endsWith("/")) "$baseUrl$track" else "$baseUrl/$track"
        val headers = mutableMapOf(
            "Transport" to "RTP/AVP/TCP;unicast;interleaved=$interleaved"
        )
        if (!authHeader.isNullOrBlank()) headers["Authorization"] = authHeader
        if (!session.isNullOrBlank()) headers["Session"] = session
        val response = sendRequest("SETUP", url, headers, cseq, null)
        val sessionHeader = response.headers["Session"] ?: session
        return sessionHeader?.substringBefore(";")
    }

    private fun buildAuthHeader(uri: URI): String? {
        val userInfo = uri.userInfo ?: return null
        val b64 = android.util.Base64.encodeToString(userInfo.toByteArray(), android.util.Base64.NO_WRAP)
        return "Basic $b64"
    }

    private fun waitForVideoConfig(): VideoEncoder.CodecConfig {
        var config: VideoEncoder.CodecConfig? = null
        var attempts = 0
        while (config == null && attempts < 20) {
            try {
                config = videoEncoder.getCodecConfig()
            } catch (_: Exception) {}
            if (config == null) {
                try { videoEncoder.requestKeyFrame() } catch (_: Exception) {}
                Thread.sleep(100)
            }
            attempts++
        }
        return config ?: throw IllegalStateException("Missing video config")
    }

    private fun waitForAudioConfig(): AudioEncoder.AudioConfig? {
        var config: AudioEncoder.AudioConfig? = null
        var attempts = 0
        while (config == null && attempts < 20) {
            try {
                config = audioEncoder?.getAudioConfig()
            } catch (_: Exception) {}
            if (config == null) Thread.sleep(100)
            attempts++
        }
        return config
    }

    private fun videoLoop(queue: LinkedBlockingQueue<VideoEncoder.EncodedFrame>) {
        val output = out ?: return
        val sender = RtpStreamSender(output, mimeType)
        try {
            videoEncoder.requestKeyFrame()
        } catch (_: Exception) {}
        val config = try { videoEncoder.getCodecConfig() } catch (_: Exception) { null }
        if (config != null) {
            if (config.vps != null) sender.sendNal(config.vps, 0, 0)
            sender.sendNal(config.sps, 0, 0)
            sender.sendNal(config.pps, 0, 0)
        }
        while (running.get()) {
            val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val ts90k = ((frame.timeUs / 1000L) * 90L).toInt()
            if (frame.keyframe) {
                val cfg = try { videoEncoder.getCodecConfig() } catch (_: Exception) { null }
                if (cfg != null) {
                    if (cfg.vps != null) sender.sendNal(cfg.vps, ts90k, 0)
                    sender.sendNal(cfg.sps, ts90k, 0)
                    sender.sendNal(cfg.pps, ts90k, 0)
                }
            }
            val nals = splitAnnexB(frame.data)
            for (nal in nals) {
                if (nal.isNotEmpty()) sender.sendNal(nal, ts90k, 0)
            }
        }
    }

    private fun audioLoop(queue: LinkedBlockingQueue<AudioEncoder.EncodedAudioFrame>, config: AudioEncoder.AudioConfig) {
        val output = out ?: return
        val sender = RtpAudioSender(output)
        val sampleRate = config.sampleRate
        while (running.get()) {
            if (Thread.currentThread().isInterrupted) break
            val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val ts = ((frame.timeUs * sampleRate) / 1_000_000L).toInt()
            sender.sendAacFrame(frame.data, ts, 2)
        }
    }

    private fun sendRequest(method: String, url: String, authHeader: String?, cseq: Int, body: String?, session: String? = null): RtspResponse {
        val headers = mutableMapOf<String, String>()
        if (!authHeader.isNullOrBlank()) headers["Authorization"] = authHeader
        if (!session.isNullOrBlank()) headers["Session"] = session
        return sendRequest(method, url, headers, cseq, body)
    }

    private fun sendRequest(method: String, url: String, headers: Map<String, String>, cseq: Int, body: String?): RtspResponse {
        val output = out ?: throw IllegalStateException("No connection")
        val sb = StringBuilder()
        sb.append("$method $url RTSP/1.0\r\n")
        sb.append("CSeq: $cseq\r\n")
        for ((k, v) in headers) {
            sb.append("$k: $v\r\n")
        }
        if (body != null) {
            sb.append("Content-Type: application/sdp\r\n")
            sb.append("Content-Length: ${body.toByteArray().size}\r\n")
        }
        sb.append("\r\n")
        synchronized(output) {
            output.write(sb.toString().toByteArray())
            if (body != null) output.write(body.toByteArray())
            output.flush()
        }
        val response = readResponse()
        if (response.code < 200 || response.code >= 300) throw RuntimeException("RTSP error ${response.code}")
        return response
    }

    private fun readResponse(): RtspResponse {
        val r = reader ?: throw IllegalStateException("No connection")
        val status = r.readLine() ?: throw RuntimeException("No RTSP response")
        val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: 500
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = r.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(":")
            if (idx > 0) {
                headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        return RtspResponse(code, headers)
    }

    private fun buildSdp(config: VideoEncoder.CodecConfig?, audioConfig: AudioEncoder.AudioConfig?, host: String): String {
        val sps = config?.sps
        val pps = config?.pps
        val vps = config?.vps
        val spsB64 = if (sps != null) android.util.Base64.encodeToString(sps, android.util.Base64.NO_WRAP) else ""
        val ppsB64 = if (pps != null) android.util.Base64.encodeToString(pps, android.util.Base64.NO_WRAP) else ""
        val vpsB64 = if (vps != null) android.util.Base64.encodeToString(vps, android.util.Base64.NO_WRAP) else ""
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
            "o=- 0 0 IN IP4 $host\r\n" +
            "s=QNVR-PUSH\r\n" +
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
            } else if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
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

    private fun closeConnection() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        reader = null
        out = null
    }

    private data class RtspResponse(val code: Int, val headers: Map<String, String>)
}
