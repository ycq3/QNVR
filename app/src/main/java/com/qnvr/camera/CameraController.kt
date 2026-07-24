package com.qnvr.camera

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
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
  private var watermarkPosition = "bottom"
  private var watermarkOpacity = 180
    private var rtspEncoder: com.qnvr.stream.VideoEncoder? = null
    private val latestPreviewJpeg = AtomicReference<ByteArray?>(null)
    private var sessionTargets: List<Surface> = emptyList()

    private var statsMonitor: com.qnvr.StatsMonitor? = null

    private var targetFps = 30
    private var targetWidth = 1920
    private var targetHeight = 1080
    private var lastFrameTime = 0L
    private var watermarkBitmap: Bitmap? = null
    private var watermarkCanvas: Canvas? = null
    private val watermarkPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 32f
        isAntiAlias = true
        isSubpixelText = true
        isDither = true
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

    private val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    // Precomputed alpha blend lookup tables for white text in YUV space (Y=255, U=128, V=128)
    // Index = (bgValue * 256) + alpha
    private val blendTableY: ByteArray = ByteArray(256 * 256)
    private val blendTableUV: ByteArray = ByteArray(256 * 256)

    // Reusable temp buffers for UV conversion to avoid repeated allocations
    private var uvTempU: ByteArray? = null
    private var uvTempV: ByteArray? = null

    init {
        for (bg in 0..255) {
            val baseIdx = bg * 256
            for (alpha in 0..255) {
                val blendedY = (bg * (255 - alpha) + 255 * alpha) / 255
                blendTableY[baseIdx + alpha] = blendedY.toByte()
                val blendedUV = (bg * (255 - alpha) + 128 * alpha) / 255
                blendTableUV[baseIdx + alpha] = blendedUV.toByte()
            }
        }
    }

    fun setEncoderSurface(surface: android.view.Surface) {
        encoderSurface = surface
        if (session != null) {
            android.util.Log.i("CameraController", "Encoder surface set, restarting session")
            restartSession()
        }
    }

    fun setResolution(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
    }

    fun setFps(newFps: Int) {
        fps = newFps
        targetFps = newFps
        val s = session ?: return
        val device = cameraDevice ?: return
        try {
            s.stopRepeating()
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            for (surf in sessionTargets) builder.addTarget(surf)
            configureCaptureRequest(builder)
            s.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            Sentry.captureException(e)
            restartSession()
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
        uvTempU = null
        uvTempV = null
    }

    private fun setupReaders() {
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supported = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888) ?: arrayOf(Size(640, 480))
        val preferred = supported.firstOrNull { it.width == targetWidth && it.height == targetHeight }
            ?: supported.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: supported.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: supported.maxBy { it.width * it.height }
        imageReader = ImageReader.newInstance(preferred!!.width, preferred.height, android.graphics.ImageFormat.YUV_420_888, 3)
        imageReader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener

            val width = img.width
            val height = img.height

            statsMonitor?.onFrame()

            val nv12 = yuv420ToNv12(img)
            img.close()

            if (rtspEncoder != null) {
                try {
                    if (enableRtspWatermark) {
                        android.util.Log.d("CameraController", "Adding watermark to frame ${width}x${height}")
                        addWatermarkDirect(nv12, width, height)
                    }
                    rtspEncoder?.feedFrame(nv12, System.nanoTime() / 1000)
                } catch (e: Exception) {
                    io.sentry.Sentry.captureException(e)
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastPreviewTime >= (1000 / previewFps)) {
                lastPreviewTime = now

                val nv21 = nv21Buffer?.takeIf { it.size == nv12.size } ?: ByteArray(nv12.size).also { nv21Buffer = it }

                System.arraycopy(nv12, 0, nv21, 0, width * height)
                var i = width * height
                while (i < nv12.size) {
                    nv21[i] = nv12[i + 1]
                    nv21[i + 1] = nv12[i]
                    i += 2
                }

                val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
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
            encoderSurface?.let {
                targets.add(it)
                android.util.Log.i("CameraController", "Added encoder surface to camera targets: $it")
            }
        }

        sessionTargets = targets.toList()
        android.util.Log.i("CameraController", "Creating session with ${sessionTargets.size} targets")
        device.createCaptureSession(sessionTargets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                for (surf in sessionTargets) builder.addTarget(surf)
                configureCaptureRequest(builder)
                try {
                    s.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            super.onCaptureCompleted(session, request, result)
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
        configureCaptureRequest(builder)
        builder.set(CaptureRequest.FLASH_MODE, if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        try {
            s.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            Sentry.captureException(e)
        }
    }

    fun setZoom(z: Float) {
        zoom = max(1.0f, min(z, getMaxDigitalZoom()))
        restartSession()
    }

    fun setWatermarkEnabled(enabled: Boolean) {
        watermark = enabled
    }

    fun setRtspWatermarkEnabled(enabled: Boolean) {
    enableRtspWatermark = enabled
  }

  fun isRtspWatermarkEnabled(): Boolean = enableRtspWatermark

  fun setWatermarkPosition(position: String) {
    watermarkPosition = if (position == "top") "top" else "bottom"
  }

  fun setWatermarkOpacity(opacity: Int) {
    watermarkOpacity = opacity.coerceIn(0, 255)
  }

  fun setRtspEncoder(encoder: com.qnvr.stream.VideoEncoder?) {
    rtspEncoder = encoder
  }

  fun setDeviceName(name: String) { deviceName = name }
  fun setShowDeviceName(show: Boolean) { showDeviceName = show }

    fun getLatestJpeg(): ByteArray? = latestPreviewJpeg.get()

    fun getRtspSuggestedUrl(ip: String): String = "rtsp://$ip:18554/live"

    /**
     * Trigger a single auto-focus scan while keeping continuous video AF as the default mode.
     */
    fun triggerFocus() {
        val device = cameraDevice ?: return
        val s = session ?: return
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        for (surf in sessionTargets) builder.addTarget(surf)
        configureCaptureRequest(builder)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        try {
            s.capture(builder.build(), null, handler)
        } catch (e: Exception) {
            Sentry.captureException(e)
        }
    }

    private fun getMaxDigitalZoom(): Float {
        val chars = manager.getCameraCharacteristics(cameraId)
        return chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
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

    private fun configureCaptureRequest(builder: CaptureRequest.Builder) {
        val chars = manager.getCameraCharacteristics(cameraId)

        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

        // Anti-banding (anti-flicker)
        val antibandingModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
        if (antibandingModes != null && antibandingModes.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }

        // Digital video stabilization
        val videoStabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        if (videoStabModes != null && videoStabModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }

        // Optical Image Stabilization (OIS)
        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (oisModes != null && oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }

        // Noise reduction: prefer HIGH_QUALITY, fallback to FAST
        val noiseModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        if (noiseModes != null) {
            when {
                noiseModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) -> {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                }
                noiseModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) -> {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                }
            }
        }

        // Edge enhancement: prefer HIGH_QUALITY, fallback to FAST
        val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        if (edgeModes != null) {
            when {
                edgeModes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY) -> {
                    builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                }
                edgeModes.contains(CaptureRequest.EDGE_MODE_FAST) -> {
                    builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                }
            }
        }

        // Tonemap
        val tonemapModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        if (tonemapModes != null && tonemapModes.contains(CaptureRequest.TONEMAP_MODE_FAST)) {
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        }

        // Color correction aberration mode
        val aberrationModes = chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
        if (aberrationModes != null && aberrationModes.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)) {
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
        }

        // Color correction mode
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)

        applyZoom(builder)
    }

    private fun yuv420ToNv12(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val totalSize = ySize + uvSize

        // Reuse buffer if possible to avoid repeated allocations
        val nv12 = nv12Buffer?.takeIf { it.size == totalSize } ?: ByteArray(totalSize).also { nv12Buffer = it }

        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride

        // Copy Y plane with optimized path for standard stride
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv12, 0, ySize)
        } else if (yPixelStride == 1) {
            for (r in 0 until height) {
                yBuffer.position(r * yRowStride)
                yBuffer.get(nv12, r * width, width)
            }
        } else {
            for (r in 0 until height) {
                val dstRowStart = r * width
                val srcRowStart = r * yRowStride
                for (c in 0 until width) {
                    nv12[dstRowStart + c] = yBuffer.get(srcRowStart + c * yPixelStride)
                }
            }
        }

        // Copy UV planes
        val uPixelStride = planes[1].pixelStride
        val vPixelStride = planes[2].pixelStride
        val uRowStride = planes[1].rowStride
        val vRowStride = planes[2].rowStride

        var outputPos = ySize
        val halfH = height shr 1
        val halfW = width shr 1

        // Fast path for standard interleaved UV (pixelStride == 2) with reusable temp buffers
        if (uPixelStride == 2 && vPixelStride == 2 && uRowStride == vRowStride) {
            val uRem = uBuffer.remaining()
            val vRem = vBuffer.remaining()
            val uBytes = uvTempU?.takeIf { it.size >= uRem } ?: ByteArray(uRem).also { uvTempU = it }
            val vBytes = uvTempV?.takeIf { it.size >= vRem } ?: ByteArray(vRem).also { uvTempV = it }
            uBuffer.get(uBytes, 0, uRem)
            vBuffer.get(vBytes, 0, vRem)

            val rowWidthBytes = halfW * 2
            for (j in 0 until halfH) {
                val rowStart = j * uRowStride
                var srcIdx = rowStart
                val srcEnd = rowStart + rowWidthBytes
                while (srcIdx < srcEnd) {
                    nv12[outputPos++] = uBytes[srcIdx]
                    nv12[outputPos++] = vBytes[srcIdx]
                    srcIdx += 2
                }
            }
        } else {
            // Slow path for non-standard strides
            for (j in 0 until halfH) {
                val uRowStart = j * uRowStride
                val vRowStart = j * vRowStride
                for (i in 0 until halfW) {
                    nv12[outputPos++] = uBuffer.get(uRowStart + i * uPixelStride)
                    nv12[outputPos++] = vBuffer.get(vRowStart + i * vPixelStride)
                }
            }
        }
        return nv12
    }

    private fun addWatermarkDirect(nv12: ByteArray, width: Int, height: Int) {
        if (!enableRtspWatermark) return

        val topH = 80
        val botH = 80
        val totalH = if (showDeviceName) topH + botH else botH

        val now = System.currentTimeMillis()
        val second = now / 1000

        var bmp = watermarkBitmap
        if (bmp == null || watermarkWidth != width || watermarkHeight != totalH) {
            watermarkWidth = width
            watermarkHeight = totalH
            bmp = Bitmap.createBitmap(width, totalH, Bitmap.Config.ARGB_8888)
            watermarkBitmap = bmp
            watermarkPixels = IntArray(width * totalH)
            watermarkCanvas = null
            lastWatermarkSecond = -1
            android.util.Log.d("CameraController", "Created watermark bitmap ${width}x${totalH}")
        }

        val pixels = watermarkPixels
        if (pixels == null) return

        if (second != lastWatermarkSecond) {
            lastWatermarkSecond = second

            val canvas = watermarkCanvas ?: Canvas(bmp!!).also { watermarkCanvas = it }
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)

            val text = dateFormat.format(java.util.Date())

            if (showDeviceName && deviceName.isNotEmpty()) {
                canvas.drawText(deviceName, 20f, 40f, watermarkPaint)
            }

            val textY = if (showDeviceName) topH + 50f else 50f
            canvas.drawText(text, 20f, textY, watermarkPaint)

            bmp!!.getPixels(pixels, 0, width, 0, 0, width, totalH)
            android.util.Log.d("CameraController", "Updated watermark text, pixels count: ${pixels.size}")
        }

        val yStart = if (watermarkPosition == "top") 0 else height - totalH
        blendRegion(nv12, pixels, yStart, 0, width, totalH, width, height)
    }

    private fun blendRegion(
        nv12: ByteArray, pixels: IntArray,
        yStart: Int, pixelYStart: Int,
        width: Int, h: Int, pixelWidth: Int, height: Int
    ) {
        val ySize = width * height
        var pIdx = pixelYStart * pixelWidth
        val tableY = blendTableY
        val tableUV = blendTableUV
        val frameWidth = width
        val bufSize = nv12.size
        val globalOpacity = watermarkOpacity
        val useTable = (globalOpacity == 255)

        for (j in 0 until h) {
            val yPos = (yStart + j) * frameWidth
            val uvRowBase = ySize + ((yStart + j) shr 1) * frameWidth
            for (i in 0 until width) {
                val c = pixels[pIdx++]
                val alpha = c ushr 24
                if (alpha == 0) continue

                val effectiveAlpha = if (useTable) alpha else (alpha * globalOpacity + 127) / 255
                if (effectiveAlpha == 0) continue

                val yIdx = yPos + i
                val bgY = nv12[yIdx].toInt() and 0xFF
                nv12[yIdx] = if (useTable) {
                    tableY[(bgY shl 8) or alpha]
                } else {
                    ((bgY * (255 - effectiveAlpha) + 255 * effectiveAlpha) / 255).toByte()
                }

                // UV is shared by 2x2 block; write only on even columns to avoid redundant stores
                if ((i and 1) == 0) {
                    val uvIdx = uvRowBase + (i shr 1) * 2
                    if (uvIdx + 1 < bufSize) {
                        val bgU = nv12[uvIdx].toInt() and 0xFF
                        val bgV = nv12[uvIdx + 1].toInt() and 0xFF
                        nv12[uvIdx] = if (useTable) {
                            tableUV[(bgU shl 8) or alpha]
                        } else {
                            ((bgU * (255 - effectiveAlpha) + 128 * effectiveAlpha) / 255).toByte()
                        }
                        nv12[uvIdx + 1] = if (useTable) {
                            tableUV[(bgV shl 8) or alpha]
                        } else {
                            ((bgV * (255 - effectiveAlpha) + 128 * effectiveAlpha) / 255).toByte()
                        }
                    }
                }
            }
        }
    }

    fun setStatsMonitor(monitor: com.qnvr.StatsMonitor) {
        statsMonitor = monitor
    }

    fun getStatsMonitor(): com.qnvr.StatsMonitor? = statsMonitor
}
