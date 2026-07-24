package com.qnvr.config

import android.content.Context
import android.media.MediaFormat

class ConfigStore(ctx: Context) {
  private val sp = ctx.getSharedPreferences("qnvr", Context.MODE_PRIVATE)
  fun getPort(): Int = sp.getInt("port", 18554)  // 将默认端口从8554改为18554
  fun setPort(v: Int) { sp.edit().putInt("port", v).apply() }
  fun getBitrate(): Int = sp.getInt("bitrate", 2_000_000)
  fun setBitrate(v: Int) { sp.edit().putInt("bitrate", v).apply() }
  fun getWidth(): Int = sp.getInt("width", 1920)
  fun getHeight(): Int = sp.getInt("height", 1080)
  fun getFps(): Int = sp.getInt("fps", 20)
  fun setFps(v: Int) { sp.edit().putInt("fps", v).apply() }
  fun setResolution(w: Int, h: Int) { sp.edit().putInt("width", w).putInt("height", h).apply() }
  fun getUsername(): String = sp.getString("username", "admin") ?: "admin"
  fun getPassword(): String = sp.getString("password", "") ?: ""
  fun setCredentials(u: String, p: String) { sp.edit().putString("username", u).putString("password", p).apply() }
  fun getDeviceName(): String = sp.getString("deviceName", android.os.Build.MODEL ?: "QNVR") ?: "QNVR"
  fun setDeviceName(v: String) { sp.edit().putString("deviceName", v).apply() }
  fun isShowDeviceName(): Boolean = sp.getBoolean("showDeviceName", false)
  fun setShowDeviceName(v: Boolean) { sp.edit().putBoolean("showDeviceName", v).apply() }
  // 新增：获取编码器名称和MIME类型
  fun getEncoderName(): String? = sp.getString("encoderName", null)
  fun setEncoderName(v: String?) { sp.edit().putString("encoderName", v).apply() }
  
  // 为不同编码格式提供合适的默认码率
  fun getMimeType(): String {
    val mimeType = sp.getString("mimeType", MediaFormat.MIMETYPE_VIDEO_HEVC) ?: MediaFormat.MIMETYPE_VIDEO_HEVC
    // 确保返回的MIME类型是有效的
    return when (mimeType) {
      MediaFormat.MIMETYPE_VIDEO_AVC,
      MediaFormat.MIMETYPE_VIDEO_HEVC -> mimeType
      else -> MediaFormat.MIMETYPE_VIDEO_HEVC  // 默认使用H.265
    }
  }
  
  fun setMimeType(v: String) { 
    // 验证MIME类型
    val validMimeType = when (v) {
      MediaFormat.MIMETYPE_VIDEO_AVC,
      MediaFormat.MIMETYPE_VIDEO_HEVC -> v
      else -> MediaFormat.MIMETYPE_VIDEO_HEVC  // 默认使用H.265
    }
    sp.edit().putString("mimeType", validMimeType).apply() 
  }

  fun isPushEnabled(): Boolean = sp.getBoolean("pushEnabled", false)
  fun setPushEnabled(v: Boolean) { sp.edit().putBoolean("pushEnabled", v).apply() }
  fun getPushUrl(): String = sp.getString("pushUrl", "") ?: ""
  fun setPushUrl(v: String) { sp.edit().putString("pushUrl", v).apply() }
  fun isPushUseRemoteConfig(): Boolean = sp.getBoolean("pushUseRemoteConfig", false)
  fun setPushUseRemoteConfig(v: Boolean) { sp.edit().putBoolean("pushUseRemoteConfig", v).apply() }
  fun getPushConfigUrl(): String = sp.getString("pushConfigUrl", "") ?: ""
  fun setPushConfigUrl(v: String) { sp.edit().putString("pushConfigUrl", v).apply() }
  
  fun isAudioEnabled(): Boolean = sp.getBoolean("audioEnabled", true)
  fun setAudioEnabled(v: Boolean) { sp.edit().putBoolean("audioEnabled", v).apply() }
  
  // 根据编码格式获取合适的默认码率
  fun getBitrateForMimeType(mimeType: String = getMimeType()): Int {
    return when (mimeType) {
      MediaFormat.MIMETYPE_VIDEO_HEVC -> 2_000_000  // HEVC最大码率原H.264一半
      else -> 4_000_000  // H.264和其他格式
    }
  }

  // I帧间隔（秒），默认1
  fun getIFrameInterval(): Int = sp.getInt("iFrameInterval", 1)
  fun setIFrameInterval(v: Int) { sp.edit().putInt("iFrameInterval", v.coerceAtLeast(1)).apply() }

  // 码率模式，默认 "cq"（尝试恒定质量，如果设备支持）
  fun getBitrateMode(): String = sp.getString("bitrateMode", "cq") ?: "cq"
  fun setBitrateMode(v: String) { sp.edit().putString("bitrateMode", v).apply() }

  // 降噪等级：off, fast, high_quality，默认 high_quality
  fun getNoiseReduction(): String = sp.getString("noiseReduction", "high_quality") ?: "high_quality"
  fun setNoiseReduction(v: String) { sp.edit().putString("noiseReduction", v).apply() }

  // 视频防抖开关，默认 true
  fun isVideoStabilization(): Boolean = sp.getBoolean("videoStabilization", true)
  fun setVideoStabilization(v: Boolean) { sp.edit().putBoolean("videoStabilization", v).apply() }

  // 边缘增强开关，默认 true
  fun isEdgeEnhancement(): Boolean = sp.getBoolean("edgeEnhancement", true)
  fun setEdgeEnhancement(v: Boolean) { sp.edit().putBoolean("edgeEnhancement", v).apply() }

  // 曝光补偿（-2 到 +2，步长0.5），默认 0
  fun getExposureCompensation(): Float = sp.getFloat("exposureCompensation", 0f)
  fun setExposureCompensation(v: Float) { sp.edit().putFloat("exposureCompensation", v.coerceIn(-2f, 2f)).apply() }

  // 水印位置：top 或 bottom，默认 bottom
  fun getWatermarkPosition(): String = sp.getString("watermarkPosition", "bottom") ?: "bottom"
  fun setWatermarkPosition(v: String) { sp.edit().putString("watermarkPosition", v).apply() }

  // 水印透明度（0-255），默认 180
  fun getWatermarkOpacity(): Int = sp.getInt("watermarkOpacity", 180)
  fun setWatermarkOpacity(v: Int) { sp.edit().putInt("watermarkOpacity", v.coerceIn(0, 255)).apply() }

  // 低功耗模式（无客户端时自动降帧率），默认 true
  fun isLowPowerMode(): Boolean = sp.getBoolean("lowPowerMode", true)
  fun setLowPowerMode(v: Boolean) { sp.edit().putBoolean("lowPowerMode", v).apply() }

  // 音频采样率：8000, 11025, 16000, 22050, 44100, 48000，默认 44100
  fun getSampleRate(): Int = sp.getInt("sampleRate", 44100)
  fun setSampleRate(v: Int) { sp.edit().putInt("sampleRate", v).apply() }
}
