package com.qnvr.stream

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

class EncoderManager {
    companion object {
        // 获取设备支持的所有视频编码器
        fun getSupportedEncoders(): List<EncoderInfo> {
            val encoders = mutableListOf<EncoderInfo>()
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            val codecs = list.codecInfos

            for (codec in codecs) {
                if (!codec.isEncoder) continue

                val types = codec.supportedTypes
                for (type in types) {
                    // 只关注视频编码器
                    if (type.startsWith("video/")) {
                        val name = codec.name
                        val isHardwareAccelerated = isHardwareAccelerated(codec)
                        encoders.add(EncoderInfo(name, type, isHardwareAccelerated))
                    }
                }
            }

            return encoders
        }

        // 判断是否为硬件加速编码器
        private fun isHardwareAccelerated(codecInfo: MediaCodecInfo): Boolean {
            // 在较新版本的Android中，可以直接使用isHardwareAccelerated方法
            return try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    codecInfo.isHardwareAccelerated
                } else {
                    // 在旧版本中，通过名称特征判断
                    val name = codecInfo.name.lowercase()
                    // 通常硬件编码器包含这些关键字
                    name.contains("omx") && !name.contains("sw") || 
                    name.contains("hardware") || 
                    name.contains("qcom") || 
                    name.contains("exynos") || 
                    name.contains("mediatek") || 
                    name.contains("rockchip")
                }
            } catch (e: Exception) {
                false
            }
        }

        // 获取推荐的编码器（优先硬件编码器）
        fun getRecommendedEncoder(mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC): EncoderInfo? {
            val encoders = getSupportedEncoders().filter { it.mimeType == mimeType }
            
            // 优先选择硬件加速编码器
            val hwEncoder = encoders.find { it.isHardwareAccelerated }
            if (hwEncoder != null) return hwEncoder
            
            // 如果没有硬件编码器，选择第一个软件编码器
            return encoders.firstOrNull()
        }
    }
}

data class EncoderInfo(
    val name: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean
) {
    fun getDisplayName(): String {
        val type = if (isHardwareAccelerated) "硬件" else "软件"
        val formatName = when (mimeType) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264/AVC"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265/HEVC"
            MediaFormat.MIMETYPE_VIDEO_VP8 -> "VP8"
            MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
            else -> mimeType
        }
        return "$formatName ($type)"
    }
}