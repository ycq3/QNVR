package com.qnvr.stream

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * 视频性能优化器
 * 实现H.265编码优化和自适应码率控制
 */
class VideoPerformanceOptimizer {
    
    companion object {
        
        /**
         * 获取优化的H.265编码配置
         */
        fun getOptimizedHevcConfig(
            width: Int,
            height: Int,
            fps: Int,
            baseBitrate: Int
        ): MediaFormat {
            val alignWidth = if (width % 2 != 0) width - 1 else width
            val alignHeight = if (height % 2 != 0) height - 1 else height
            
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, alignWidth, alignHeight)
            
            // 优化码率设置（根据您的实施手册）
            val optimizedBitrate = (baseBitrate * 0.5).toInt().coerceAtLeast(100000) // H.265相比H.264可节省50%码率
            format.setInteger(MediaFormat.KEY_BIT_RATE, optimizedBitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            
            // 关键帧间隔优化
            val gopSize = fps * 10 // GOP = 帧率 × 10
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, gopSize / fps)
            
            // H.265特定优化
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                
                // 启用10位色深支持（如果设备支持）
                try {
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                } catch (e: Exception) {
                    Log.w("VideoOptimizer", "设备不支持YUV420Flexible，使用默认格式")
                }
            }
            
            // 低延迟模式
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    format.setInteger(MediaFormat.KEY_LATENCY, 1)
                } catch (e: Exception) {
                    Log.w("VideoOptimizer", "无法设置低延迟模式")
                }
            }
            
            // 启用B帧（提高压缩效率）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 2)
            }
            
            Log.i("VideoOptimizer", "H.265优化配置: ${alignWidth}x${alignHeight}, ${optimizedBitrate}bps, ${fps}fps")
            
            return format
        }
        
        /**
         * 自适应码率控制算法
         */
        fun calculateAdaptiveBitrate(
            currentBitrate: Int,
            networkQuality: Float, // 0.0-1.0，网络质量评分
            frameComplexity: Float, // 0.0-1.0，画面复杂度
            bufferLevel: Float // 0.0-1.0，缓冲区填充程度
        ): Int {
            // 基础码率调整因子
            var adjustmentFactor = 1.0f
            
            // 网络质量影响（网络差时降低码率）
            adjustmentFactor *= networkQuality
            
            // 画面复杂度影响（复杂画面需要更高码率）
            adjustmentFactor *= (0.7f + 0.3f * frameComplexity)
            
            // 缓冲区影响（缓冲区满时降低码率）
            if (bufferLevel > 0.8f) {
                adjustmentFactor *= 0.8f // 缓冲区接近满时降低20%
            } else if (bufferLevel < 0.2f) {
                adjustmentFactor *= 1.2f // 缓冲区空时提高20%
            }
            
            // 限制调整范围（0.3x - 1.5x）
            adjustmentFactor = adjustmentFactor.coerceIn(0.3f, 1.5f)
            
            val newBitrate = (currentBitrate * adjustmentFactor).toInt()
            
            Log.i("VideoOptimizer", "自适应码率调整: $currentBitrate -> $newBitrate (因子: $adjustmentFactor)")
            
            return newBitrate
        }
        
        /**
         * 检查设备是否支持H.265硬件编码
         */
        fun isHevcHardwareEncodingSupported(): Boolean {
            return try {
                val codecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
                val codecInfos = codecList.codecInfos
                
                codecInfos.any { info ->
                    info.isEncoder && 
                    info.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC) &&
                    info.isHardwareAccelerated
                }
            } catch (e: Exception) {
                Log.e("VideoOptimizer", "检查H.265支持失败", e)
                false
            }
        }
        
        /**
         * 获取推荐的编码器配置
         */
        fun getRecommendedEncoderConfig(resolution: String): EncoderConfig {
            return when (resolution) {
                "1080p" -> EncoderConfig(1920, 1080, 2000000, 20) // 2Mbps, 20fps
                "720p" -> EncoderConfig(1280, 720, 1000000, 15)   // 1Mbps, 15fps
                "480p" -> EncoderConfig(640, 480, 500000, 15)     // 500kbps, 15fps
                else -> EncoderConfig(1280, 720, 1000000, 15)
            }
        }
    }
    
    data class EncoderConfig(
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val fps: Int
    )
}