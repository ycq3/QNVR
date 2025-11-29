package com.qnvr.config

import android.content.Context

class ConfigStore(ctx: Context) {
  private val sp = ctx.getSharedPreferences("qnvr", Context.MODE_PRIVATE)
  fun getPort(): Int = sp.getInt("port", 8554)
  fun setPort(v: Int) { sp.edit().putInt("port", v).apply() }
  fun getBitrate(): Int = sp.getInt("bitrate", 2_000_000)
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
}
