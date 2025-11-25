package com.qnvr.preview

import com.qnvr.camera.CameraController
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class MjpegStreamer(private val cameraController: CameraController) {
  private val running = AtomicBoolean(false)
  fun start() { running.set(true) }
  fun stop() { running.set(false) }
  fun isRunning(): Boolean = running.get()

  fun nextFrame(): ByteArray? {
    if (!running.get()) return null
    return cameraController.getLatestJpeg()
  }
}
