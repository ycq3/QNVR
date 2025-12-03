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
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,  // 编码格式
    private val useSurfaceInput: Boolean = true // 是否使用Surface输入
) {
    private lateinit var codec: MediaCodec
    private var inputSurface: Surface? = null
    private val outQueue = LinkedBlockingQueue<EncodedFrame>()
  private var vps: ByteArray? = null
  private var sps: ByteArray? = null
  private var pps: ByteArray? = null
  private var isStarted = false
  
  data class EncodedFrame(val data: ByteArray, val timeUs: Long, val keyframe: Boolean)
  data class CodecConfig(val vps: ByteArray?, val sps: ByteArray, val pps: ByteArray)

  fun start() {
    try {
      android.util.Log.i("VideoEncoder", "Starting video encoder with mimeType: $mimeType, resolution: ${width}x${height}, bitrate: $bitrate")
      startEncoder()
      isStarted = true
      Thread { drainLoop() }.start()
      android.util.Log.i("VideoEncoder", "Video encoder started successfully")
    } catch (e: Exception) {
      android.util.Log.e("VideoEncoder", "Failed to start video encoder", e)
      Sentry.captureException(e)
      throw e
    }
  }

  private fun startEncoder() {
    val format = MediaFormat.createVideoFormat(mimeType, width, height)
    
    // Use EncoderManager to find the best encoder
    var selectedEncoder: EncoderInfo? = null
    
    if (encoderName != null) {
      android.util.Log.i("VideoEncoder", "Requesting specific encoder: $encoderName")
      val specific = EncoderManager.getEncoderByName(encoderName)
      if (specific != null && specific.mimeType == mimeType) {
        selectedEncoder = specific
      } else {
        android.util.Log.w("VideoEncoder", "Requested encoder $encoderName not found or does not support $mimeType")
      }
    }
    
    if (selectedEncoder == null) {
      android.util.Log.i("VideoEncoder", "Finding best encoder for $mimeType")
      selectedEncoder = EncoderManager.getBestEncoder(mimeType, true)
    }
    
    if (selectedEncoder == null) {
      throw RuntimeException("No suitable encoder found for $mimeType")
    }
    
    android.util.Log.i("VideoEncoder", "Selected encoder: ${selectedEncoder.name} (${selectedEncoder.getDisplayName()})")
    
    try {
      codec = MediaCodec.createByCodecName(selectedEncoder.name)
    } catch (e: Exception) {
      android.util.Log.e("VideoEncoder", "Failed to create codec ${selectedEncoder.name}", e)
      // Fallback to default if specific creation fails (unlikely if EncoderManager found it, but safe)
      try {
        codec = MediaCodec.createEncoderByType(mimeType)
        android.util.Log.w("VideoEncoder", "Fallback to default createEncoderByType for $mimeType")
      } catch (e2: Exception) {
        throw e
      }
    }

    // 设置颜色格式
    if (useSurfaceInput) {
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    } else {
        // 使用 Buffer 输入时，通常使用 YUV420SemiPlanar (NV12)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
    }
    
    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
    format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    
    // Try to set profile/level for better compatibility if needed (optional optimization)
    // For HEVC, Main Profile is standard. For AVC, High or Main.
    
    android.util.Log.i("VideoEncoder", "Configuring codec with format: $format")
    
    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      if (useSurfaceInput) {
        inputSurface = codec.createInputSurface()
      }
      codec.start()
      android.util.Log.i("VideoEncoder", "Codec started successfully")
    } catch (e: Exception) {
      android.util.Log.e("VideoEncoder", "Failed to configure or start codec", e)
      Sentry.captureException(e)
      throw e
    }
  }

  fun stop() {
    android.util.Log.i("VideoEncoder", "Stopping video encoder")
    isStarted = false
    try { codec.stop() } catch (_: Exception) {}
    try { codec.release() } catch (_: Exception) {}
    android.util.Log.i("VideoEncoder", "Video encoder stopped")
  }

  fun getInputSurface(): Surface? = inputSurface

  fun feedFrame(data: ByteArray, timeUs: Long) {
    if (useSurfaceInput || !isStarted) return
    try {
        val index = codec.dequeueInputBuffer(10000)
        if (index >= 0) {
            val buffer = codec.getInputBuffer(index)
            buffer?.clear()
            buffer?.put(data)
            codec.queueInputBuffer(index, 0, data.size, timeUs, 0)
        }
    } catch (e: Exception) {
        android.util.Log.e("VideoEncoder", "Error feeding frame", e)
    }
  }

  fun poll(timeoutUs: Long = 0): EncodedFrame? {
    return if (timeoutUs > 0) {
      outQueue.poll(timeoutUs, java.util.concurrent.TimeUnit.MICROSECONDS)
    } else {
      outQueue.poll()
    }
  }

  fun getCodecConfig(): CodecConfig? {
    val s = sps ?: return null
    val p = pps ?: return null
    return CodecConfig(vps, s, p)
  }

  // Keep for backward compatibility if needed, or remove if we update all callers
  fun getSpsPps(): Pair<ByteArray, ByteArray>? {
    val config = getCodecConfig() ?: return null
    return Pair(config.sps, config.pps)
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
          android.util.Log.e("VideoEncoder", "Error in drain loop", e)
          Sentry.captureException(e)
        }
        break
      }
    }
  }

  private fun parseSpsPps(conf: ByteArray) {
    var i = 0
    while (i + 2 < conf.size) {
      // Check for 00 00 00 01 (4 bytes)
      if (i + 3 < conf.size && conf[i].toInt() == 0 && conf[i + 1].toInt() == 0 && conf[i + 2].toInt() == 0 && conf[i + 3].toInt() == 1) {
        val start = i + 4
        var j = start
        while (j + 2 < conf.size) {
          if (j + 3 < conf.size && conf[j].toInt() == 0 && conf[j + 1].toInt() == 0 && conf[j + 2].toInt() == 0 && conf[j + 3].toInt() == 1) break
          if (conf[j].toInt() == 0 && conf[j + 1].toInt() == 0 && conf[j + 2].toInt() == 1) break
          j++
        }
        val end = if (j + 2 < conf.size) j else conf.size
        val nal = conf.copyOfRange(start, end)
        processConfigNal(nal)
        i = j
      } 
      // Check for 00 00 01 (3 bytes)
      else if (conf[i].toInt() == 0 && conf[i + 1].toInt() == 0 && conf[i + 2].toInt() == 1) {
        val start = i + 3
        var j = start
        while (j + 2 < conf.size) {
          if (j + 3 < conf.size && conf[j].toInt() == 0 && conf[j + 1].toInt() == 0 && conf[j + 2].toInt() == 0 && conf[j + 3].toInt() == 1) break
          if (conf[j].toInt() == 0 && conf[j + 1].toInt() == 0 && conf[j + 2].toInt() == 1) break
          j++
        }
        val end = if (j + 2 < conf.size) j else conf.size
        val nal = conf.copyOfRange(start, end)
        processConfigNal(nal)
        i = j
      } else {
        i++
      }
    }
  }

  private fun processConfigNal(nal: ByteArray) {
    if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
      val type = (nal[0].toInt() shr 1) and 0x3F
      if (type == 32) vps = nal
      if (type == 33) sps = nal
      if (type == 34) pps = nal
    } else {
      val type = nal[0].toInt() and 0x1F
      if (type == 7) sps = nal
      if (type == 8) pps = nal
    }
  }
}