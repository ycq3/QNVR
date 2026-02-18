package com.qnvr.config

import android.content.Context
import android.media.MediaFormat

class ConfigStore(ctx: Context) {
  private val sp = ctx.getSharedPreferences("qnvr", Context.MODE_PRIVATE)
  fun getPort(): Int = sp.getInt("port", 18554)  // 将默认端口从8554改为18554
  fun setPort(v: Int) { sp.edit().putInt("port", v).apply() }
  fun getBitrate(): Int = sp.getInt("bitrate", 4_000_000)
  fun setBitrate(v: Int) { sp.edit().putInt("bitrate", v).apply() }
  fun getWidth(): Int = sp.getInt("width", 1280)
  fun getHeight(): Int = sp.getInt("height", 720)
  fun getFps(): Int = sp.getInt("fps", 30)
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
    val mimeType = sp.getString("mimeType", MediaFormat.MIMETYPE_VIDEO_AVC) ?: MediaFormat.MIMETYPE_VIDEO_AVC
    // 确保返回的MIME类型是有效的
    return when (mimeType) {
      MediaFormat.MIMETYPE_VIDEO_AVC,
      MediaFormat.MIMETYPE_VIDEO_HEVC -> mimeType
      else -> MediaFormat.MIMETYPE_VIDEO_AVC  // 默认使用H.264
    }
  }
  
  fun setMimeType(v: String) { 
    // 验证MIME类型
    val validMimeType = when (v) {
      MediaFormat.MIMETYPE_VIDEO_AVC,
      MediaFormat.MIMETYPE_VIDEO_HEVC -> v
      else -> MediaFormat.MIMETYPE_VIDEO_AVC  // 默认使用H.264
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
  
  // 根据编码格式获取合适的默认码率
  fun getBitrateForMimeType(mimeType: String = getMimeType()): Int {
    return when (mimeType) {
      MediaFormat.MIMETYPE_VIDEO_HEVC -> 3_000_000  // HEVC通常需要较低码率
      else -> 4_000_000  // H.264和其他格式
    }
  }
}
