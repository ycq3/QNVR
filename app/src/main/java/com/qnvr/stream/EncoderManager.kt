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
                    if (type != MediaFormat.MIMETYPE_VIDEO_AVC && type != MediaFormat.MIMETYPE_VIDEO_HEVC) {
                        continue
                    }
                        try {
                            val caps = codec.getCapabilitiesForType(type)
                            val videoCaps = caps.videoCapabilities
                            val colorFormats = caps.colorFormats
                            
                            val name = codec.name
                            val isHardwareAccelerated = isHardwareAccelerated(codec)
                            
                            encoders.add(EncoderInfo(
                                name, 
                                type, 
                                isHardwareAccelerated,
                                videoCaps.supportedWidths,
                                videoCaps.supportedHeights,
                                videoCaps.bitrateRange,
                                colorFormats
                            ))
                        } catch (e: Exception) {
                            android.util.Log.e("EncoderManager", "Failed to get capabilities for $type on ${codec.name}", e)
                        }
                }
            }

            return encoders
        }

        // 根据名称获取编码器信息
        fun getEncoderByName(name: String): EncoderInfo? {
            return getSupportedEncoders().find { it.name == name }
        }

        // 获取推荐的编码器
        fun getBestEncoder(mimeType: String, preferHardware: Boolean = true): EncoderInfo? {
            val encoders = getSupportedEncoders().filter { it.mimeType == mimeType }
            
            if (encoders.isEmpty()) return null
            
            // 1. 尝试找到硬件编码器
            if (preferHardware) {
                val hw = encoders.find { it.isHardwareAccelerated }
                if (hw != null) return hw
            }
            
            // 2. 如果没有硬件编码器或不强制硬件，返回列表中的第一个
            return encoders.firstOrNull()
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

    }
}

data class EncoderInfo(
    val name: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val supportedWidths: android.util.Range<Int>? = null,
    val supportedHeights: android.util.Range<Int>? = null,
    val bitrateRange: android.util.Range<Int>? = null,
    val colorFormats: IntArray? = null
) {
    fun getDisplayName(): String {
        val type = if (isHardwareAccelerated) "硬件" else "软件"
        val formatName = when (mimeType) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264/AVC"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265/HEVC"
            else -> mimeType
        }
        return "$formatName ($type) - $name"
    }
    
    override fun toString(): String {
        return "EncoderInfo(name='$name', mimeType='$mimeType', hw=$isHardwareAccelerated, w=$supportedWidths, h=$supportedHeights, bitrate=$bitrateRange)"
    }
}
