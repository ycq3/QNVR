package com.qnvr.camera

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

class CameraController(private val context: Context) {
  private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  private var handlerThread: HandlerThread? = null
  private var handler: Handler? = null
  private var cameraDevice: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var imageReader: ImageReader? = null
  private var encoderSurface: android.view.Surface? = null
  private var cameraId: String = "0"
  private var zoom = 1.0f
  private var watermark = true
  private var fps = 30
  private val latestPreviewJpeg = AtomicReference<ByteArray?>(null)

  fun setEncoderSurface(surface: android.view.Surface) {
    encoderSurface = surface
    if (session != null) restartSession()
  }

  @SuppressLint("MissingPermission")
  fun start() {
    handlerThread = HandlerThread("CameraThread")
    handlerThread!!.start()
    handler = Handler(handlerThread!!.looper)
    for (id in manager.cameraIdList) {
      val chars = manager.getCameraCharacteristics(id)
      val facing = chars.get(CameraCharacteristics.LENS_FACING)
      if (facing == CameraCharacteristics.LENS_FACING_BACK) {
        cameraId = id
        break
      }
    }
    manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
      override fun onOpened(device: CameraDevice) {
        cameraDevice = device
        setupReaders()
        createSession()
      }
      override fun onDisconnected(device: CameraDevice) { device.close() }
      override fun onError(device: CameraDevice, error: Int) { device.close() }
    }, handler)
  }

  fun stop() {
    session?.close()
    cameraDevice?.close()
    imageReader?.close()
    handlerThread?.quitSafely()
  }

  private fun setupReaders() {
    val size = Size(1280, 720)
    imageReader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.YUV_420_888, 3)
    imageReader!!.setOnImageAvailableListener({ r ->
      val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
      val data = yuvToJpeg(img)
      img.close()
      val withWatermark = if (watermark) addTimeWatermark(data) else data
      latestPreviewJpeg.set(withWatermark)
    }, handler)
  }

  private fun createSession() {
    val device = cameraDevice ?: return
    val targets = mutableListOf<android.view.Surface>()
    imageReader?.surface?.let { targets.add(it) }
    encoderSurface?.let { targets.add(it) }
    device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(s: CameraCaptureSession) {
        session = s
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        imageReader?.surface?.let { builder.addTarget(it) }
        encoderSurface?.let { builder.addTarget(it) }
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        applyZoom(builder)
        s.setRepeatingRequest(builder.build(), null, handler)
      }
      override fun onConfigureFailed(s: CameraCaptureSession) {}
    }, handler)
  }

  private fun restartSession() {
    session?.close()
    createSession()
  }

  fun setTorch(on: Boolean) {
    val device = cameraDevice ?: return
    val s = session ?: return
    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
    imageReader?.surface?.let { builder.addTarget(it) }
    encoderSurface?.let { builder.addTarget(it) }
    builder.set(CaptureRequest.FLASH_MODE, if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
    applyZoom(builder)
    s.setRepeatingRequest(builder.build(), null, handler)
  }

  fun setZoom(z: Float) {
    zoom = max(1.0f, min(z, getMaxDigitalZoom()))
    restartSession()
  }

  fun setWatermarkEnabled(enabled: Boolean) {
    watermark = enabled
  }

  fun getLatestJpeg(): ByteArray? = latestPreviewJpeg.get()

  fun getRtspSuggestedUrl(ip: String): String = "rtsp://$ip:8554/live"

  private fun getMaxDigitalZoom(): Float {
    val chars = manager.getCameraCharacteristics(cameraId)
    val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
    return maxZoom
  }

  private fun applyZoom(builder: CaptureRequest.Builder) {
    val chars = manager.getCameraCharacteristics(cameraId)
    val rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
    val ratio = 1.0f / zoom
    val w = (rect.width() * ratio).toInt()
    val h = (rect.height() * ratio).toInt()
    val x = (rect.width() - w) / 2
    val y = (rect.height() - h) / 2
    val crop = Rect(rect.left + x, rect.top + y, rect.left + x + w, rect.top + y + h)
    builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
  }

  private fun yuvToJpeg(image: Image): ByteArray {
    val nv21 = yuv420ToNv21(image)
    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 70, out)
    return out.toByteArray()
  }

  private fun yuv420ToNv21(image: Image): ByteArray {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    val chromaRowStride = image.planes[1].rowStride
    val chromaPixelStride = image.planes[1].pixelStride
    var offset = ySize
    val width = image.width
    val height = image.height
    val u = ByteArray(uSize)
    val v = ByteArray(vSize)
    uBuffer.get(u, 0, uSize)
    vBuffer.get(v, 0, vSize)
    var i = 0
    while (i < height / 2) {
      var j = 0
      while (j < width / 2) {
        val uIndex = i * chromaRowStride + j * chromaPixelStride
        val vIndex = i * chromaRowStride + j * chromaPixelStride
        nv21[offset++] = v[vIndex]
        nv21[offset++] = u[uIndex]
        j++
      }
      i++
    }
    return nv21
  }

  private fun addTimeWatermark(jpeg: ByteArray): ByteArray {
    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
    val c = Canvas(mutable)
    val p = Paint()
    p.color = android.graphics.Color.WHITE
    p.textSize = 32f
    p.isAntiAlias = true
    val text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
    c.drawText(text, 20f, mutable.height - 40f, p)
    val out = ByteArrayOutputStream()
    mutable.compress(Bitmap.CompressFormat.JPEG, 70, out)
    return out.toByteArray()
  }
}
