package com.qnvr.config

interface ConfigApplier {
  fun applyPort(port: Int)
  fun applyEncoder(width: Int, height: Int, bitrate: Int, fps: Int)
  fun applyDeviceName(name: String)
  fun applyShowDeviceName(show: Boolean)
  fun applyCredentials(username: String, password: String)
}