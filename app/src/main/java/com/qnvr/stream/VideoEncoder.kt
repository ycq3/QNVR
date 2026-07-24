package com.qnvr.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import io.sentry.Sentry

class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val encoderName: String? = null,
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    private val useSurfaceInput: Boolean = true,
    private val lowLatencyMode: Boolean = true,
    private val enableFrameDrop: Boolean = true,
    private val enableAdaptiveBitrate: Boolean = true
) {
    private lateinit var codec: MediaCodec
    private var inputSurface: Surface? = null
    private val callbacks = java.util.concurrent.CopyOnWriteArrayList<FrameCallback>()
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var isStarted = false
    private var selectedEncoder: EncoderInfo? = null
    private var adaptiveBitrateController: AdaptiveBitrateController? = null
    private var currentAdaptiveBitrate: Int = bitrate
    private var lastStopTime: Long = 0L
    private val encoderRestartDelayMs: Long = 500L

    data class EncodedFrame(val data: ByteArray, val timeUs: Long, val keyframe: Boolean)
    data class CodecConfig(val vps: ByteArray?, val sps: ByteArray, val pps: ByteArray)

    interface FrameCallback {
        fun onFrame(frame: EncodedFrame)
    }

    fun addCallback(callback: FrameCallback) {
        callbacks.add(callback)
    }

    fun removeCallback(callback: FrameCallback) {
        callbacks.remove(callback)
    }

    fun start(context: android.content.Context? = null) {
        try {
            android.util.Log.i("VideoEncoder", "Starting video encoder with mimeType: $mimeType, resolution: ${width}x${height}, bitrate: $bitrate")

            val timeSinceLastStop = System.currentTimeMillis() - lastStopTime
            if (timeSinceLastStop < encoderRestartDelayMs) {
                val waitTime = encoderRestartDelayMs - timeSinceLastStop
                android.util.Log.i("VideoEncoder", "Waiting ${waitTime}ms before restarting encoder to allow hardware resources to be released")
                Thread.sleep(waitTime)
            }

            startEncoder()
            isStarted = true
            Thread { drainLoop() }.start()
            android.util.Log.i("VideoEncoder", "Video encoder started successfully")
        } catch (e: Exception) {
            android.util.Log.e("VideoEncoder", "Failed to start video encoder", e)
            Sentry.captureException(e)
            throw e
        }
    }

    private fun startEncoder() {
        val alignWidth = if (width % 2 != 0) width - 1 else width
        val alignHeight = if (height % 2 != 0) height - 1 else height

        val format = MediaFormat.createVideoFormat(mimeType, alignWidth, alignHeight)

        if (encoderName != null) {
            android.util.Log.i("VideoEncoder", "Requesting specific encoder: $encoderName")
            val specific = EncoderManager.getEncoderByName(encoderName)
            if (specific != null && specific.mimeType == mimeType) {
                selectedEncoder = specific
            } else {
                android.util.Log.w("VideoEncoder", "Requested encoder $encoderName not found or does not support $mimeType")
            }
        }

        if (selectedEncoder == null) {
            android.util.Log.i("VideoEncoder", "Finding best encoder for $mimeType")
            selectedEncoder = EncoderManager.getBestEncoder(mimeType, true)
        }

        if (selectedEncoder == null) {
            throw RuntimeException("No suitable encoder found for $mimeType")
        }

        android.util.Log.i("VideoEncoder", "Selected encoder: ${selectedEncoder!!.name} (${selectedEncoder!!.getDisplayName()})")

        try {
            codec = MediaCodec.createByCodecName(selectedEncoder!!.name)
        } catch (e: Exception) {
            android.util.Log.e("VideoEncoder", "Failed to create codec ${selectedEncoder!!.name}", e)
            try {
                codec = MediaCodec.createEncoderByType(mimeType)
                android.util.Log.w("VideoEncoder", "Fallback to default createEncoderByType for $mimeType")
            } catch (e2: Exception) {
                throw e
            }
        }

        if (useSurfaceInput) {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        } else {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        }

        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            format.setInteger(MediaFormat.KEY_MAX_BIT_RATE, bitrate * 2)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 2)
        }

        if (lowLatencyMode && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                format.setInteger(MediaFormat.KEY_LATENCY, 1)
            } catch (e: Exception) {
                android.util.Log.w("VideoEncoder", "Failed to set latency", e)
            }
        }

        try {
            val codecInfo = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.find { it.name == selectedEncoder!!.name }
            if (codecInfo != null) {
                val caps = codecInfo.getCapabilitiesForType(mimeType)
                val profileLevels = caps.profileLevels
                val supportedProfiles = profileLevels.map { it.profile }.toSet()

                var bestProfile = -1

                if (mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    if (supportedProfiles.contains(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)) {
                        bestProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                        android.util.Log.i("VideoEncoder", "Selecting AVC High Profile")
                    } else if (supportedProfiles.contains(MediaCodecInfo.CodecProfileLevel.AVCProfileMain)) {
                        bestProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                        android.util.Log.i("VideoEncoder", "Selecting AVC Main Profile")
                    } else if (supportedProfiles.contains(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)) {
                        bestProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                        android.util.Log.i("VideoEncoder", "Selecting AVC Baseline Profile")
                    }
                } else if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                    if (supportedProfiles.contains(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)) {
                        bestProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                        android.util.Log.i("VideoEncoder", "Selecting HEVC Main Profile")
                    }
                }

                if (bestProfile != -1) {
                    format.setInteger(MediaFormat.KEY_PROFILE, bestProfile)
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    val encoderCaps = caps.encoderCapabilities

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        val complexityRange = encoderCaps.complexityRange
                        if (complexityRange != null && !complexityRange.isEmpty) {
                            format.setInteger(MediaFormat.KEY_COMPLEXITY, complexityRange.lower)
                            android.util.Log.i("VideoEncoder", "Setting complexity to lowest (${complexityRange.lower})")
                        }
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        when {
                            encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) -> {
                                format.setInteger("bitrate-mode", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                                android.util.Log.i("VideoEncoder", "Using CQ bitrate mode")
                            }
                            encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) -> {
                                format.setInteger("bitrate-mode", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                                android.util.Log.i("VideoEncoder", "Using VBR bitrate mode")
                            }
                        }
                    } else {
                        format.setInteger("bitrate-mode", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("VideoEncoder", "Failed to configure advanced encoder parameters", e)
        }

        if (selectedEncoder!!.isHardwareAccelerated) {
            trySetHardwareSpecificOptions()
        }

        android.util.Log.i("VideoEncoder", "Configuring codec with format: $format")

        try {
            configureAndStart(format)
        } catch (e: Exception) {
            @Suppress("NewApi")
            fun removeKeys() {
                if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                    format.removeKey(MediaFormat.KEY_PROFILE)
                    if (format.containsKey(MediaFormat.KEY_LEVEL)) format.removeKey(MediaFormat.KEY_LEVEL)
                }

                if (format.containsKey("bitrate-mode")) {
                    format.removeKey("bitrate-mode")
                }

                if (format.containsKey(MediaFormat.KEY_LATENCY)) {
                    format.removeKey(MediaFormat.KEY_LATENCY)
                }

                if (format.containsKey(MediaFormat.KEY_COMPLEXITY)) {
                    format.removeKey(MediaFormat.KEY_COMPLEXITY)
                }

                if (format.containsKey(MediaFormat.KEY_MAX_BIT_RATE)) {
                    format.removeKey(MediaFormat.KEY_MAX_BIT_RATE)
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                removeKeys()
            }

            android.util.Log.i("VideoEncoder", "Retrying with relaxed format: $format")

            try {
                releaseResources()
                Thread.sleep(encoderRestartDelayMs)
                codec = MediaCodec.createByCodecName(selectedEncoder!!.name)

                configureAndStart(format)
            } catch (e2: Exception) {
                android.util.Log.e("VideoEncoder", "Second attempt failed, trying software encoder fallback", e2)

                try {
                    releaseResources()
                    Thread.sleep(encoderRestartDelayMs)
                    codec = MediaCodec.createEncoderByType(mimeType)
                    val fallbackFormat = MediaFormat.createVideoFormat(mimeType, alignWidth, alignHeight)
                    fallbackFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        if (useSurfaceInput) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                    fallbackFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    fallbackFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    fallbackFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                    android.util.Log.i("VideoEncoder", "Retrying with system default encoder and basic format: $fallbackFormat")
                    configureAndStart(fallbackFormat)
                } catch (e3: Exception) {
                    android.util.Log.e("VideoEncoder", "All fallback attempts failed", e3)
                    throw e3
                }
            }
        }
    }

    private fun configureAndStart(format: MediaFormat) {
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            android.util.Log.e("VideoEncoder", "Codec configure failed: ${e.message}", e)
            throw e
        }
        if (useSurfaceInput) {
            inputSurface = codec.createInputSurface()
            android.util.Log.i("VideoEncoder", "Created input surface: $inputSurface")
        }
        try {
            codec.start()
            android.util.Log.i("VideoEncoder", "Codec started successfully")
        } catch (e: Exception) {
            android.util.Log.e("VideoEncoder", "Codec start failed after configure, attempting to release and retry", e)
            try {
                releaseResources()
            } catch (_: Exception) {}
            throw e
        }
    }

    private fun releaseResources() {
        android.util.Log.i("VideoEncoder", "Releasing encoder resources")
        try {
            if (::codec.isInitialized) {
                codec.stop()
            }
        } catch (e: Exception) {
            android.util.Log.w("VideoEncoder", "Error stopping codec", e)
        }
        try {
            if (::codec.isInitialized) {
                codec.release()
            }
        } catch (e: Exception) {
            android.util.Log.w("VideoEncoder", "Error releasing codec", e)
        }
        try {
            inputSurface?.release()
        } catch (e: Exception) {
            android.util.Log.w("VideoEncoder", "Error releasing input surface", e)
        }
        inputSurface = null
        android.util.Log.i("VideoEncoder", "Encoder resources released")
    }

    private fun trySetHardwareSpecificOptions() {
        try {
            val encoderNameLower = selectedEncoder?.name?.lowercase() ?: ""

            if (encoderNameLower.contains("qcom") || encoderNameLower.contains("omx.qcom")) {
                android.util.Log.i("VideoEncoder", "Applying Qualcomm specific optimizations")
            }

            if (encoderNameLower.contains("exynos") || encoderNameLower.contains("omx.exynos")) {
                android.util.Log.i("VideoEncoder", "Applying Exynos specific optimizations")
            }

            if (encoderNameLower.contains("mediatek") || encoderNameLower.contains("omx.mtk")) {
                android.util.Log.i("VideoEncoder", "Applying MediaTek specific optimizations")
            }
        } catch (e: Exception) {
            android.util.Log.w("VideoEncoder", "Failed to set hardware specific options", e)
        }
    }

    fun stop() {
        android.util.Log.i("VideoEncoder", "Stopping video encoder")
        isStarted = false
        lastStopTime = System.currentTimeMillis()
        releaseResources()
        sps = null
        pps = null
        vps = null
        android.util.Log.i("VideoEncoder", "Video encoder stopped at $lastStopTime")
    }

    fun getInputSurface(): Surface? = inputSurface

    fun feedFrame(data: ByteArray, timeUs: Long) {
        if (useSurfaceInput || !isStarted) return
        try {
            val index = codec.dequeueInputBuffer(10000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)
                buffer?.clear()
                buffer?.put(data)
                codec.queueInputBuffer(index, 0, data.size, timeUs, 0)
            } else {
                android.util.Log.w("VideoEncoder", "No input buffer available")
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoEncoder", "Error feeding frame", e)
        }
    }

    fun poll(): EncodedFrame? {
        return null
    }

    fun getCodecConfig(): CodecConfig? {
        val s = sps ?: return null
        val p = pps ?: return null
        return CodecConfig(vps, s, p)
    }

    fun getSelectedEncoderName(): String? = selectedEncoder?.name

    fun getSpsPps(): Pair<ByteArray, ByteArray>? {
        val config = getCodecConfig() ?: return null
        return Pair(config.sps, config.pps)
    }

    fun requestKeyFrame() {
        if (isStarted) {
            try {
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                codec.setParameters(params)
                android.util.Log.i("VideoEncoder", "Requested key frame")
            } catch (e: Exception) {
                android.util.Log.e("VideoEncoder", "Failed to request key frame", e)
            }
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (isStarted) {
            try {
                val index = codec.dequeueOutputBuffer(info, 10000)
                if (index >= 0) {
                    val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                    if (isConfig || isKeyFrame || callbacks.isNotEmpty()) {
                        val buf = codec.getOutputBuffer(index) ?: continue
                        val size = info.size
                        if (size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + size)
                            val data = ByteArray(size)
                            buf.get(data)

                            if (isConfig || isKeyFrame) {
                                parseSpsPps(data)
                                android.util.Log.i("VideoEncoder", "Parsed config/keyframe: isKeyFrame=$isKeyFrame, isConfig=$isConfig, sps=${sps?.size}, pps=${pps?.size}")
                            }

                            if (callbacks.isNotEmpty()) {
                                val frame = EncodedFrame(data, info.presentationTimeUs, isKeyFrame || isConfig)
                                for (cb in callbacks) {
                                    cb.onFrame(frame)
                                }
                            }
                        }
                    }

                    codec.releaseOutputBuffer(index, false)
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    parseCodecConfigFromFormat(codec.outputFormat)
                    android.util.Log.i("VideoEncoder", "Output format changed: ${codec.outputFormat}")
                }
            } catch (e: Exception) {
                if (isStarted) {
                    android.util.Log.e("VideoEncoder", "Error in drain loop", e)
                    Sentry.captureException(e)
                }
                break
            }
        }
    }

    private fun parseCodecConfigFromFormat(format: MediaFormat) {
        try {
            if (format.containsKey("csd-0")) {
                format.getByteBuffer("csd-0")?.let { parseSpsPps(byteBufferToArray(it)) }
            }
            if (format.containsKey("csd-1")) {
                format.getByteBuffer("csd-1")?.let { parseSpsPps(byteBufferToArray(it)) }
            }
            if (format.containsKey("csd-2")) {
                format.getByteBuffer("csd-2")?.let { parseSpsPps(byteBufferToArray(it)) }
            }
            android.util.Log.i("VideoEncoder", "Parsed codec config from output format, hasSps=${sps != null}, hasPps=${pps != null}, hasVps=${vps != null}")
        } catch (e: Exception) {
            android.util.Log.w("VideoEncoder", "Failed to parse codec config from output format", e)
        }
    }

    private fun byteBufferToArray(buffer: ByteBuffer): ByteArray {
        val dup = buffer.duplicate()
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        return bytes
    }

    private fun parseSpsPps(conf: ByteArray) {
        val nals = splitNalUnits(conf)
        if (nals.isEmpty() && conf.isNotEmpty()) {
            processConfigNal(conf)
            return
        }
        for (nal in nals) {
            processConfigNal(nal)
        }
    }

    private fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val annexB = splitAnnexBNals(data)
        if (annexB.isNotEmpty()) return annexB
        return splitLengthPrefixedNals(data)
    }

    private fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i + 2 < data.size) {
            if (i + 3 < data.size && data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                val start = i + 4
                var j = start
                while (j + 2 < data.size) {
                    if (j + 3 < data.size && data[j].toInt() == 0 && data[j + 1].toInt() == 0 && data[j + 2].toInt() == 0 && data[j + 3].toInt() == 1) break
                    if (data[j].toInt() == 0 && data[j + 1].toInt() == 0 && data[j + 2].toInt() == 1) break
                    j++
                }
                val end = if (j + 2 < data.size) j else data.size
                if (start < end) {
                    out.add(data.copyOfRange(start, end))
                }
                i = j
            } else if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                val start = i + 3
                var j = start
                while (j + 2 < data.size) {
                    if (j + 3 < data.size && data[j].toInt() == 0 && data[j + 1].toInt() == 0 && data[j + 2].toInt() == 0 && data[j + 3].toInt() == 1) break
                    if (data[j].toInt() == 0 && data[j + 1].toInt() == 0 && data[j + 2].toInt() == 1) break
                    j++
                }
                val end = if (j + 2 < data.size) j else data.size
                if (start < end) {
                    out.add(data.copyOfRange(start, end))
                }
                i = j
            } else {
                i++
            }
        }
        return out
    }

    private fun splitLengthPrefixedNals(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 4 <= data.size) {
            val nalSize = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            if (nalSize <= 0 || offset + 4 + nalSize > data.size) {
                return emptyList()
            }
            out.add(data.copyOfRange(offset + 4, offset + 4 + nalSize))
            offset += 4 + nalSize
        }
        return if (offset == data.size) out else emptyList()
    }

    private fun processConfigNal(nal: ByteArray) {
        if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            val type = (nal[0].toInt() shr 1) and 0x3F
            if (type == 32) vps = nal
            if (type == 33) sps = nal
            if (type == 34) pps = nal
        } else {
            val type = nal[0].toInt() and 0x1F
            if (type == 7) sps = nal
            if (type == 8) pps = nal
        }
    }

    fun adjustBitrate(
        frameComplexity: Float = 0.5f,
        bufferLevel: Float = 0.5f,
        packetLossRate: Float = 0.0f
    ) {
        if (!enableAdaptiveBitrate || adaptiveBitrateController == null) return

        val newBitrate = adaptiveBitrateController!!.getCurrentBitrate(
            frameComplexity = frameComplexity,
            bufferLevel = bufferLevel,
            packetLossRate = packetLossRate
        )

        if (newBitrate != currentAdaptiveBitrate && isStarted) {
            currentAdaptiveBitrate = newBitrate

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    val params = android.os.Bundle()
                    params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                    codec.setParameters(params)

                    android.util.Log.i("VideoEncoder", "动态调整码率: ${newBitrate/1000}kbps")
                }
            } catch (e: Exception) {
                android.util.Log.w("VideoEncoder", "动态调整码率失败", e)
            }
        }
    }

    fun getAdaptiveBitrateStats(): AdaptiveBitrateController.AdaptiveBitrateStats? {
        return adaptiveBitrateController?.getStatistics()
    }

    fun setBitrateManually(bitrate: Int) {
        adaptiveBitrateController?.setBitrate(bitrate)
        currentAdaptiveBitrate = bitrate

        if (isStarted) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    val params = android.os.Bundle()
                    params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate)
                    codec.setParameters(params)

                    android.util.Log.i("VideoEncoder", "手动设置码率: ${bitrate/1000}kbps")
                }
            } catch (e: Exception) {
                android.util.Log.w("VideoEncoder", "手动设置码率失败", e)
            }
        }
    }

    fun resetAdaptiveBitrate() {
        adaptiveBitrateController?.reset()
        currentAdaptiveBitrate = bitrate

        android.util.Log.i("VideoEncoder", "重置自适应码率控制")
    }

    fun getCurrentBitrate(): Int = currentAdaptiveBitrate
}