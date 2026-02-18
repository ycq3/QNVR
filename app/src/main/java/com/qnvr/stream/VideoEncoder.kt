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
    private val encoderName: String? = null,
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    private val useSurfaceInput: Boolean = true,
    private val lowLatencyMode: Boolean = true,
    private val enableFrameDrop: Boolean = true
) {
    private lateinit var codec: MediaCodec
    private var inputSurface: Surface? = null
    // Remove the single queue
    // private val outQueue = LinkedBlockingQueue<EncodedFrame>(50)
    private val callbacks = java.util.concurrent.CopyOnWriteArrayList<FrameCallback>()
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var isStarted = false
    private var selectedEncoder: EncoderInfo? = null
  
  data class EncodedFrame(val data: ByteArray, val timeUs: Long, val keyframe: Boolean)
  data class CodecConfig(val vps: ByteArray?, val sps: ByteArray, val pps: ByteArray)

  interface FrameCallback {
      fun onFrame(frame: EncodedFrame)
  }

  fun addCallback(callback: FrameCallback) {
      callbacks.add(callback)
  }

  fun removeCallback(callback: FrameCallback) {
      callbacks.remove(callback)
  }

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
    // Ensure dimensions are even numbers (some encoders fail with odd dimensions)
    val alignWidth = if (width % 2 != 0) width - 1 else width
    val alignHeight = if (height % 2 != 0) height - 1 else height
    
    val format = MediaFormat.createVideoFormat(mimeType, alignWidth, alignHeight)
    
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
    
    android.util.Log.i("VideoEncoder", "Selected encoder: ${selectedEncoder!!.name} (${selectedEncoder!!.getDisplayName()})")
    
    try {
      codec = MediaCodec.createByCodecName(selectedEncoder!!.name)
    } catch (e: Exception) {
      android.util.Log.e("VideoEncoder", "Failed to create codec ${selectedEncoder!!.name}", e)
      try {
        codec = MediaCodec.createEncoderByType(mimeType)
        android.util.Log.w("VideoEncoder", "Fallback to default createEncoderByType for $mimeType")
      } catch (e2: Exception) {
        throw e
      }
    }

    if (useSurfaceInput) {
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    } else {
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
    }
    
    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
    format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    
    // Prefer VBR over CBR for compatibility, or check capabilities
    // format.setInteger("bitrate-mode", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
    // Use default bitrate mode or let the system decide, or try VBR if available
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
        // Use "bitrate-mode" string literal as KEY_BIT_RATE_MODE is API 21+
        // and some build environments might have issues resolving it if compileSdk is low
        format.setInteger("bitrate-mode", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
    }
    
    if (lowLatencyMode && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        try {
            format.setInteger(MediaFormat.KEY_LATENCY, 1)
        } catch (e: Exception) {
            android.util.Log.w("VideoEncoder", "Failed to set latency", e)
        }
    }
    
    try {
        val codecInfo = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.find { it.name == selectedEncoder!!.name }
        if (codecInfo != null) {
            val caps = codecInfo.getCapabilitiesForType(mimeType)
            val profileLevels = caps.profileLevels
            val supportedProfiles = profileLevels.map { it.profile }.toSet()
            
            var bestProfile = -1
            
            if (mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                if (supportedProfiles.contains(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)) {
                    bestProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    android.util.Log.i("VideoEncoder", "Selecting AVC Baseline Profile for low latency")
                } else if (supportedProfiles.contains(MediaCodecInfo.CodecProfileLevel.AVCProfileMain)) {
                    bestProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    android.util.Log.i("VideoEncoder", "Selecting AVC Main Profile")
                }
            } else if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                if (supportedProfiles.contains(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)) {
                    bestProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    android.util.Log.i("VideoEncoder", "Selecting HEVC Main Profile")
                }
            }
            
            if (bestProfile != -1) {
                format.setInteger(MediaFormat.KEY_PROFILE, bestProfile)
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("VideoEncoder", "Failed to configure profile", e)
    }
    
    if (selectedEncoder!!.isHardwareAccelerated) {
        trySetHardwareSpecificOptions(format)
    }
    
    android.util.Log.i("VideoEncoder", "Configuring codec with format: $format")
    
    try {
      configureAndStart(format)
    } catch (e: Exception) {
      android.util.Log.w("VideoEncoder", "First attempt to configure codec failed, trying fallback options", e)
      
      // Fallback 1: Remove profile/level constraints
      if (format.containsKey(MediaFormat.KEY_PROFILE)) {
          format.removeKey(MediaFormat.KEY_PROFILE)
          // Also remove level just in case, though usually profile implies level
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
             if (format.containsKey(MediaFormat.KEY_LEVEL)) format.removeKey(MediaFormat.KEY_LEVEL)
          }
      }
      
      // Fallback 2: Remove bitrate-mode (revert to default)
      if (format.containsKey("bitrate-mode")) {
          format.removeKey("bitrate-mode")
      }
      
      // Fallback 3: Remove low-latency (latency key)
      if (format.containsKey(MediaFormat.KEY_LATENCY)) {
          format.removeKey(MediaFormat.KEY_LATENCY)
      }

      android.util.Log.i("VideoEncoder", "Retrying with relaxed format: $format")
      
      try {
          // Re-create codec instance as it might be in a bad state after configure failure
          try { codec.release() } catch (_: Exception) {}
          codec = MediaCodec.createByCodecName(selectedEncoder!!.name)
          
          configureAndStart(format)
      } catch (e2: Exception) {
          android.util.Log.e("VideoEncoder", "Second attempt failed, trying software encoder fallback", e2)
          
          // Fallback 4: Try default encoder (system choice) which might be software
          try {
              try { codec.release() } catch (_: Exception) {}
              codec = MediaCodec.createEncoderByType(mimeType)
              // Reset format to basic
              val fallbackFormat = MediaFormat.createVideoFormat(mimeType, alignWidth, alignHeight)
              fallbackFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
                  if (useSurfaceInput) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface 
                  else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
              fallbackFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
              fallbackFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
              fallbackFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
              
              android.util.Log.i("VideoEncoder", "Retrying with system default encoder and basic format: $fallbackFormat")
              configureAndStart(fallbackFormat)
          } catch (e3: Exception) {
              android.util.Log.e("VideoEncoder", "All fallback attempts failed", e3)
              throw e3
          }
      }
    }
  }

  private fun configureAndStart(format: MediaFormat) {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      if (useSurfaceInput) {
        inputSurface = codec.createInputSurface()
      }
      codec.start()
      android.util.Log.i("VideoEncoder", "Codec started successfully")
  }
  
  private fun trySetHardwareSpecificOptions(format: MediaFormat) {
      try {
          val encoderNameLower = selectedEncoder?.name?.lowercase() ?: ""
          
          if (encoderNameLower.contains("qcom") || encoderNameLower.contains("omx.qcom")) {
              android.util.Log.i("VideoEncoder", "Applying Qualcomm specific optimizations")
          }
          
          if (encoderNameLower.contains("exynos") || encoderNameLower.contains("omx.exynos")) {
              android.util.Log.i("VideoEncoder", "Applying Exynos specific optimizations")
          }
          
          if (encoderNameLower.contains("mediatek") || encoderNameLower.contains("omx.mtk")) {
              android.util.Log.i("VideoEncoder", "Applying MediaTek specific optimizations")
          }
      } catch (e: Exception) {
          android.util.Log.w("VideoEncoder", "Failed to set hardware specific options", e)
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
    // Deprecated: use callbacks instead
    return null
  }

  fun getCodecConfig(): CodecConfig? {
    val s = sps ?: return null
    val p = pps ?: return null
    return CodecConfig(vps, s, p)
  }

  fun getSelectedEncoderName(): String? = selectedEncoder?.name

  fun getSpsPps(): Pair<ByteArray, ByteArray>? {
    val config = getCodecConfig() ?: return null
    return Pair(config.sps, config.pps)
  }

  fun requestKeyFrame() {
      if (isStarted) {
          try {
              val params = android.os.Bundle()
              params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
              codec.setParameters(params)
              android.util.Log.i("VideoEncoder", "Requested key frame")
          } catch (e: Exception) {
              android.util.Log.e("VideoEncoder", "Failed to request key frame", e)
          }
      }
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
          
          // Always try to parse SPS/PPS from keyframes if missing
          val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
          val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
          
          if (isConfig || (isKeyFrame && sps == null)) {
            parseSpsPps(data)
          }

          val frame = EncodedFrame(data, info.presentationTimeUs, isKeyFrame || isConfig)
          
          for (cb in callbacks) {
              cb.onFrame(frame)
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
