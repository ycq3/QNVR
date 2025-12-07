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
  private var rtspEncoder: com.qnvr.stream.VideoEncoder? = null
  private val latestPreviewJpeg = AtomicReference<ByteArray?>(null)
  private var sessionTargets: List<Surface> = emptyList()

  private var targetFps = 30
  private var lastFrameTime = 0L
  // Watermark resources
  private var watermarkBitmap: Bitmap? = null
  private var watermarkCanvas: Canvas? = null
  private val watermarkPaint = Paint().apply {
      color = android.graphics.Color.WHITE
      textSize = 32f
      isAntiAlias = true
      setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
  }
  private var lastWatermarkSecond = -1L

  fun setEncoderSurface(surface: android.view.Surface) {
    encoderSurface = surface
    if (session != null) restartSession()
  }

  fun setFps(newFps: Int) {
      fps = newFps
      targetFps = newFps
      if (session != null) {
          try {
              // Update capture request with new FPS range
              session!!.stopRepeating()
              val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
              for (surf in sessionTargets) builder.addTarget(surf)
              builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(newFps, newFps))
              builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
              builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
              applyZoom(builder)
              session!!.setRepeatingRequest(builder.build(), null, handler)
          } catch (e: Exception) {
              Sentry.captureException(e)
              restartSession()
          }
      }
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
      
      // FPS Control: Drop frames if we are going too fast
      val now = System.currentTimeMillis()
      if (now - lastFrameTime < (1000 / targetFps) - 5) { // 5ms tolerance
          img.close()
          return@setOnImageAvailableListener
      }
      lastFrameTime = now
      
      val width = img.width
      val height = img.height
      
      // Convert directly to NV12 (YUV420SemiPlanar) which is standard for MediaCodec
      val nv12 = yuv420ToNv12(img)
      img.close()

      // RTSP Stream feeding
      if (enableRtspWatermark && rtspEncoder != null) {
          try {
              // Optimized watermark: Overlay on NV12 buffer directly
              addWatermarkDirect(nv12, width, height)
              rtspEncoder?.feedFrame(nv12, System.nanoTime() / 1000)
          } catch (e: Exception) {
              io.sentry.Sentry.captureException(e)
          }
      }

      // HTTP Preview (JPEG) - only if needed
      // Note: This uses NV12 now, YuvImage supports NV21. 
      // NV12 and NV21 only differ in UV order. YuvImage expects NV21.
      // If we want preview to work, we might need to swap UV for preview or use a method that handles NV12.
      // However, for performance, we should prioritize RTSP. 
      // Let's create a quick NV12->NV21 for preview if needed, or just let it have swapped colors (Blue/Red swapped).
      // Given the user complained about RTSP lag, we prioritize that.
      // For preview, let's just swap the bytes quickly or accept wrong colors for now to save CPU.
      // Actually, YuvImage officially only supports NV21.
      
      if (latestPreviewJpeg.get() == null || watermark) { // Simple check to avoid work if nobody watching? No, preview is polled.
           // For preview, we can just use the NV12 buffer. Colors will be swapped (Red<->Blue), but it's fast.
           // Or we can swap UV in place if we don't mind messing up RTSP (we can't).
           // Let's clone for preview if we really need correct colors, or just live with swapped colors for preview.
           // Or better: write a quick swap routine.
           
           // For now, let's keep RTSP fast.
           // Convert NV12 to NV21 for preview (Swap U/V)
           val nv21 = ByteArray(nv12.size)
           System.arraycopy(nv12, 0, nv21, 0, width * height) // Copy Y
           for (i in width * height until nv12.size step 2) { // Swap UV
               nv21[i] = nv12[i + 1]
               nv21[i + 1] = nv12[i]
           }
           val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
           val out = ByteArrayOutputStream()
           yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 70, out)
           val jpegData = out.toByteArray()
           latestPreviewJpeg.set(jpegData)
      }
    }, handler)
  }

  private fun createSession() {
    val device = cameraDevice ?: return
    val targets = mutableListOf<Surface>()
    imageReader?.surface?.let { targets.add(it) }
    if (!enableRtspWatermark) {
      encoderSurface?.let { targets.add(it) }
    }
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

  fun isRtspWatermarkEnabled(): Boolean = enableRtspWatermark

  fun setRtspEncoder(encoder: com.qnvr.stream.VideoEncoder?) {
    rtspEncoder = encoder
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

  private fun yuv420ToNv12(image: Image): ByteArray {
    val width = image.width
    val height = image.height
    val ySize = width * height
    val uvSize = width * height / 2
    val nv12 = ByteArray(ySize + uvSize)

    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    // Copy Y
    if (image.planes[0].pixelStride == 1) {
        val len = min(yBuffer.remaining(), ySize)
        yBuffer.get(nv12, 0, len)
    } else {
        val rowStride = image.planes[0].rowStride
        for (r in 0 until height) {
           yBuffer.position(r * rowStride)
           yBuffer.get(nv12, r * width, width)
        }
    }

    // Copy UV
    val uPixelStride = image.planes[1].pixelStride
    val vPixelStride = image.planes[2].pixelStride
    val uRowStride = image.planes[1].rowStride
    val vRowStride = image.planes[2].rowStride
    
    var outputPos = ySize
    val halfH = height / 2
    val halfW = width / 2
    
    // Fast path for pixelStride == 2 (Standard on Android)
    if (uPixelStride == 2 && vPixelStride == 2 && uRowStride == vRowStride) {
        val uBytes = ByteArray(uBuffer.remaining())
        val vBytes = ByteArray(vBuffer.remaining())
        uBuffer.get(uBytes)
        vBuffer.get(vBytes)
        
        for (j in 0 until halfH) {
            val rowStart = j * uRowStride
            for (i in 0 until halfW) {
               val idx = rowStart + i * 2
               nv12[outputPos++] = uBytes[idx]
               nv12[outputPos++] = vBytes[idx]
            }
        }
    } else {
        // Slow path
        for (j in 0 until halfH) {
            for (i in 0 until halfW) {
               val uOffset = j * uRowStride + i * uPixelStride
               val vOffset = j * vRowStride + i * vPixelStride
               nv12[outputPos++] = uBuffer.get(uOffset)
               nv12[outputPos++] = vBuffer.get(vOffset)
            }
        }
    }
    return nv12
  }

  private fun addWatermarkDirect(nv12: ByteArray, width: Int, height: Int) {
    if (!enableRtspWatermark) return
    
    val now = System.currentTimeMillis()
    val second = now / 1000
    if (second != lastWatermarkSecond || watermarkBitmap == null || watermarkBitmap!!.width != width) {
        lastWatermarkSecond = second
        if (watermarkBitmap == null || watermarkBitmap!!.width != width) {
            watermarkBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val canvas = watermarkCanvas ?: Canvas(watermarkBitmap!!).also { watermarkCanvas = it }
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        
        val text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
        canvas.drawText(text, 20f, height - 30f, watermarkPaint)
        if (showDeviceName && deviceName.isNotEmpty()) {
            canvas.drawText(deviceName, 20f, 40f, watermarkPaint)
        }
    }
    
    val bmp = watermarkBitmap!!
    val topH = 80
    val botH = 80
    
    fun blend(yStart: Int, h: Int) {
        val pixels = IntArray(width * h)
        bmp.getPixels(pixels, 0, width, 0, yStart, width, h)
        
        var pIdx = 0
        for (j in 0 until h) {
            val yPos = (yStart + j) * width
            for (i in 0 until width) {
                val c = pixels[pIdx++]
                if ((c ushr 24) > 128) { 
                    nv12[yPos + i] = 255.toByte()
                }
            }
        }
    }
    
    if (showDeviceName) blend(0, topH)
    blend(height - botH, botH)
  }
}
