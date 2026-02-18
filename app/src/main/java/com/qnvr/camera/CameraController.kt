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
  private var enableRtspWatermark = true
  private var rtspEncoder: com.qnvr.stream.VideoEncoder? = null
  private val latestPreviewJpeg = AtomicReference<ByteArray?>(null)
  private var sessionTargets: List<Surface> = emptyList()
  
  private var statsMonitor: com.qnvr.StatsMonitor? = null

  private var targetFps = 30
  private var lastFrameTime = 0L
  private var watermarkBitmap: Bitmap? = null
  private var watermarkCanvas: Canvas? = null
  private val watermarkPaint = Paint().apply {
      color = android.graphics.Color.WHITE
      textSize = 32f
      isAntiAlias = true
      setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
  }
  private var lastWatermarkSecond = -1L
  private var watermarkPixels: IntArray? = null
  private var watermarkWidth = 0
  private var watermarkHeight = 0
  
  private var nv12Buffer: ByteArray? = null
  private var nv21Buffer: ByteArray? = null
  private val jpegOutputStream = ByteArrayOutputStream()
  private var lastPreviewTime = 0L
  private val previewFps = 5

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
    
    // Clean up reusable buffers to free memory
    nv12Buffer = null
    nv21Buffer = null
    jpegOutputStream.reset()
    watermarkBitmap?.recycle()
    watermarkBitmap = null
    watermarkCanvas = null
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
      
      statsMonitor?.onFrame()
      
      val width = img.width
      val height = img.height
      
      // Convert directly to NV12 (YUV420SemiPlanar) which is standard for MediaCodec
      val nv12 = yuv420ToNv12(img)
      img.close()

      // RTSP Stream feeding
      if (enableRtspWatermark && rtspEncoder != null) {
          try {
              // 复制一份数据给 RTSP 编码器，避免与预览共享缓冲区导致数据竞争
              val nv12Copy = nv12.copyOf()
              // Optimized watermark: Overlay on NV12 buffer directly
              addWatermarkDirect(nv12Copy, width, height)
              rtspEncoder?.feedFrame(nv12Copy, System.nanoTime() / 1000)
          } catch (e: Exception) {
              io.sentry.Sentry.captureException(e)
          }
      }

      // HTTP Preview (JPEG) - Rate limited to reduce memory pressure
      // Only generate preview at 5 FPS (200ms interval) to avoid excessive allocations
      if (now - lastPreviewTime >= (1000 / previewFps)) {
           lastPreviewTime = now
           
           // Reuse NV21 buffer to avoid repeated allocations
           val nv21 = if (nv21Buffer != null && nv21Buffer!!.size == nv12.size) {
               nv21Buffer!!
           } else {
               ByteArray(nv12.size).also { nv21Buffer = it }
           }
           
           // Convert NV12 to NV21 for preview (Swap U/V)
           System.arraycopy(nv12, 0, nv21, 0, width * height) // Copy Y
           for (i in width * height until nv12.size step 2) { // Swap UV
               nv21[i] = nv12[i + 1]
               nv21[i + 1] = nv12[i]
           }
           
           val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
           // Reuse ByteArrayOutputStream to avoid repeated allocations
           jpegOutputStream.reset()
           yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 70, jpegOutputStream)
           val jpegData = jpegOutputStream.toByteArray()
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
    val totalSize = ySize + uvSize
    
    // Reuse buffer if possible to avoid repeated allocations
    val nv12 = if (nv12Buffer != null && nv12Buffer!!.size == totalSize) {
        nv12Buffer!!
    } else {
        ByteArray(totalSize).also { nv12Buffer = it }
    }

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
        
        val topH = 80
        val botH = 80
        val totalH = if (showDeviceName) topH + botH else botH
        
        if (watermarkBitmap == null || watermarkWidth != width || watermarkHeight != totalH) {
            watermarkWidth = width
            watermarkHeight = totalH
            watermarkBitmap = Bitmap.createBitmap(width, totalH, Bitmap.Config.ARGB_8888)
            watermarkPixels = IntArray(width * totalH)
        }
        
        val bmp = watermarkBitmap!!
        val canvas = watermarkCanvas ?: Canvas(bmp).also { watermarkCanvas = it }
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        
        val text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
        val yOffset = if (showDeviceName) topH else 0
        canvas.drawText(text, 20f, (totalH - 30).toFloat(), watermarkPaint)
        
        if (showDeviceName && deviceName.isNotEmpty()) {
            canvas.drawText(deviceName, 20f, 40f, watermarkPaint)
        }
        
        bmp.getPixels(watermarkPixels!!, 0, width, 0, 0, width, totalH)
    }
    
    val pixels = watermarkPixels ?: return
    val topH = 80
    val botH = 80
    
    if (showDeviceName) {
        blendRegion(nv12, pixels, 0, 0, width, topH, width)
    }
    
    val botYStart = if (showDeviceName) topH else 0
    blendRegion(nv12, pixels, height - botH, botYStart, width, botH, width)
  }
  
  private fun blendRegion(nv12: ByteArray, pixels: IntArray, yStart: Int, pixelYStart: Int, width: Int, h: Int, pixelWidth: Int) {
      var pIdx = pixelYStart * pixelWidth
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
  
  fun setStatsMonitor(monitor: com.qnvr.StatsMonitor) {
      statsMonitor = monitor
  }
  
  fun getStatsMonitor(): com.qnvr.StatsMonitor? = statsMonitor
}
