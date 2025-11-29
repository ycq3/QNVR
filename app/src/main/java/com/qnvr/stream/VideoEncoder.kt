package com.qnvr.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import io.sentry.Sentry

class VideoEncoder(
    private val width: Int, 
    private val height: Int, 
    private val fps: Int, 
    private val bitrate: Int, 
    private val encoderName: String? = null,  // 指定编码器名称
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC  // 编码格式
) {
  private lateinit var codec: MediaCodec
  private lateinit var inputSurface: Surface
  private val outQueue = LinkedBlockingQueue<EncodedFrame>()
  private var sps: ByteArray? = null
  private var pps: ByteArray? = null
  private var isStarted = false
  
  data class EncodedFrame(val data: ByteArray, val timeUs: Long, val keyframe: Boolean)

  fun start() {
    try {
      startEncoder()
      isStarted = true
      Thread { drainLoop() }.start()
    } catch (e: Exception) {
      Sentry.captureException(e)
      throw e
    }
  }

  private fun startEncoder() {
    val format = MediaFormat.createVideoFormat(mimeType, width, height)
    
    // 根据编码器类型设置颜色格式
    if (encoderName != null) {
      // 使用指定的编码器
      codec = MediaCodec.createByCodecName(encoderName)
      // 根据编码器支持的颜色格式进行设置
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    } else {
      // 使用默认编码器
      codec = MediaCodec.createEncoderByType(mimeType)
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    }
    
    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
    format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    inputSurface = codec.createInputSurface()
    codec.start()
  }

  fun stop() {
    isStarted = false
    try { codec.stop() } catch (_: Exception) {}
    try { codec.release() } catch (_: Exception) {}
  }

  fun getInputSurface(): Surface = inputSurface

  fun poll(): EncodedFrame? = outQueue.poll()

  fun getSpsPps(): Pair<ByteArray, ByteArray>? {
    val a = sps ?: return null
    val b = pps ?: return null
    return Pair(a, b)
  }

  private fun drainLoop() {
    val info = MediaCodec.BufferInfo()
    while (isStarted) {
      try {
        val index = codec.dequeueOutputBuffer(info, 10000)
        if (index >= 0) {
          val buf = codec.getOutputBuffer(index) ?: continue
          val data = ByteArray(info.size)
          buf.position(info.offset)
          buf.limit(info.offset + info.size)
          buf.get(data)
          if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            parseSpsPps(data)
          } else {
            // 使用新的API替代已弃用的BUFFER_FLAG_SYNC_FRAME
            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            outQueue.offer(EncodedFrame(data, info.presentationTimeUs, key))
          }
          codec.releaseOutputBuffer(index, false)
        }
      } catch (e: Exception) {
        if (isStarted) {
          Sentry.captureException(e)
        }
        break
      }
    }
  }

  private fun parseSpsPps(conf: ByteArray) {
    var i = 0
    while (i + 4 < conf.size) {
      if (conf[i].toInt() == 0 && conf[i + 1].toInt() == 0 && conf[i + 2].toInt() == 0 && conf[i + 3].toInt() == 1) {
        val start = i + 4
        var j = start
        while (j + 4 < conf.size && !(conf[j].toInt() == 0 && conf[j + 1].toInt() == 0 && conf[j + 2].toInt() == 0 && conf[j + 3].toInt() == 1)) j++
        val nal = conf.copyOfRange(start, if (j + 4 < conf.size) j else conf.size)
        val type = nal[0].toInt() and 0x1F
        if (type == 7) sps = nal
        if (type == 8) pps = nal
        i = j
      } else i++
    }
  }
}