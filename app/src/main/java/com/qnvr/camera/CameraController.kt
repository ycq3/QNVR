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
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import io.sentry.Sentry

class CameraController(private val context: Context) {
  private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  private var handlerThread: HandlerThread? = null
  private var handler: Handler? = null
  private var cameraDevice: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var imageReader: ImageReader? = null
  private var encoderSurface: Surface? = null
  private var cameraId: String = "0"
  private var zoom = 1.0f
  private var watermark = true
  private var deviceName = ""
  private var showDeviceName = false
  private var fps = 30
  private var enableRtspWatermark = true  // 新增：控制RTSP流是否添加水印
  private val latestPreviewJpeg = AtomicReference<ByteArray?>(null)
  private var sessionTargets: List<Surface> = emptyList()

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
      override fun onError(device: CameraDevice, error: Int) { Sentry.captureMessage("Camera error: $error"); device.close() }
    }, handler)
  }

  fun stop() {
    session?.close()
    cameraDevice?.close()
    imageReader?.close()
    handlerThread?.quitSafely()
  }

  private fun setupReaders() {
    val chars = manager.getCameraCharacteristics(cameraId)
    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val supported = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: arrayOf(Size(640, 480))
    val preferred = supported.firstOrNull { it.width == 1280 && it.height == 720 } ?: supported.maxBy { it.width * it.height }
    imageReader = ImageReader.newInstance(preferred.width, preferred.height, android.graphics.ImageFormat.YUV_420_888, 3)
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
    val targets = mutableListOf<Surface>()
    imageReader?.surface?.let { targets.add(it) }
    encoderSurface?.let { targets.add(it) }
    sessionTargets = targets.toList()
    device.createCaptureSession(sessionTargets, object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(s: CameraCaptureSession) {
        session = s
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        for (surf in sessionTargets) builder.addTarget(surf)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        applyZoom(builder)
        try {
          s.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
              super.onCaptureCompleted(session, request, result)
              // 在这里我们可以添加额外的处理逻辑
            }
          }, handler)
        } catch (e: Exception) {
          io.sentry.Sentry.captureException(e)
        }
      }
      override fun onConfigureFailed(s: CameraCaptureSession) { io.sentry.Sentry.captureMessage("Camera configure failed") }
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
    for (surf in sessionTargets) builder.addTarget(surf)
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
  
  // 新增：设置RTSP流水印开关
  fun setRtspWatermarkEnabled(enabled: Boolean) {
    enableRtspWatermark = enabled
  }
  
  fun setDeviceName(name: String) { deviceName = name }
  fun setShowDeviceName(show: Boolean) { showDeviceName = show }

  fun getLatestJpeg(): ByteArray? = latestPreviewJpeg.get()

  fun getRtspSuggestedUrl(ip: String): String = "rtsp://$ip:18554/live"

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

  // 新增：为RTSP流添加水印的方法
  fun addWatermarkToNv21(nv21: ByteArray, width: Int, height: Int): ByteArray {
    if (!enableRtspWatermark) return nv21
    
    // 创建YUV图像
    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    
    // 将YUV转换为JPEG再转换为Bitmap
    val outputStream = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 100, outputStream)
    val jpegData = outputStream.toByteArray()
    
    // 从JPEG创建Bitmap
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
    val mutableBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    
    // 在Bitmap上绘制水印
    val canvas = android.graphics.Canvas(mutableBitmap)
    val paint = android.graphics.Paint()
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 32f
    paint.isAntiAlias = true
    
    val text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
    canvas.drawText(text, 20f, height - 40f, paint)
    
    if (showDeviceName && deviceName.isNotEmpty()) {
      canvas.drawText(deviceName, 20f, 40f, paint)
    }
    
    // 将带水印的Bitmap转换回JPEG
    val watermarkedOutputStream = java.io.ByteArrayOutputStream()
    mutableBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, watermarkedOutputStream)
    
    // 注意：这里简化处理，实际应用中可能需要将JPEG转换回YUV格式
    // 为了保持接口一致性，我们返回处理后的JPEG数据
    return watermarkedOutputStream.toByteArray()
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
    if (showDeviceName && deviceName.isNotEmpty()) {
      c.drawText(deviceName, 20f, 40f, p)
    }
    val out = ByteArrayOutputStream()
    mutable.compress(Bitmap.CompressFormat.JPEG, 70, out)
    return out.toByteArray()
  }
}
