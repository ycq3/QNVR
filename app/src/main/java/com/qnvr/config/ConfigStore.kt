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
  fun setResolution(w: Int, h: Int) { sp.edit().putInt("width", w).putInt("height", h).apply() }
  fun getUsername(): String = sp.getString("username", "admin") ?: "admin"
  fun getPassword(): String = sp.getString("password", "") ?: ""
  fun setCredentials(u: String, p: String) { sp.edit().putString("username", u).putString("password", p).apply() }
  fun getDeviceName(): String = sp.getString("deviceName", android.os.Build.MODEL ?: "QNVR") ?: "QNVR"
  fun setDeviceName(v: String) { sp.edit().putString("deviceName", v).apply() }
  fun isShowDeviceName(): Boolean = sp.getBoolean("showDeviceName", false)
  fun setShowDeviceName(v: Boolean) { sp.edit().putBoolean("showDeviceName", v).apply() }
  // 编码器配置
  fun getUseSoftwareEncoder(): Boolean = sp.getBoolean("useSoftwareEncoder", false)
  fun setUseSoftwareEncoder(v: Boolean) { sp.edit().putBoolean("useSoftwareEncoder", v).apply() }
  // 新增：获取编码器名称和MIME类型
  fun getEncoderName(): String? = sp.getString("encoderName", null)
  fun setEncoderName(v: String?) { sp.edit().putString("encoderName", v).apply() }
  fun getMimeType(): String = sp.getString("mimeType", MediaFormat.MIMETYPE_VIDEO_AVC) ?: MediaFormat.MIMETYPE_VIDEO_AVC
  fun setMimeType(v: String) { sp.edit().putString("mimeType", v).apply() }
}