package com.qnvr.stream

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * 自适应码率控制器
 * 根据网络状况和视频复杂度动态调整编码码率
 */
class AdaptiveBitrateController(
    private val initialBitrate: Int,
    private val minBitrate: Int = 100000,  // 100kbps
    private val maxBitrate: Int = 8000000, // 8Mbps
    private val context: android.content.Context? = null
) {
    
    companion object {
        private const val TAG = "AdaptiveBitrate"
        
        // 码率调整步长（百分比）
        private const val BITRATE_STEP_SMALL = 0.1f   // 10%
        private const val BITRATE_STEP_MEDIUM = 0.2f  // 20%
        private const val BITRATE_STEP_LARGE = 0.4f   // 40%
        
        // 网络质量阈值
        private const val NETWORK_EXCELLENT = 0.8f
        private const val NETWORK_GOOD = 0.6f
        private const val NETWORK_FAIR = 0.4f
        private const val NETWORK_POOR = 0.2f
        
        // 缓冲区阈值
        private const val BUFFER_HIGH = 0.8f
        private const val BUFFER_LOW = 0.2f
    }
    
    private var currentBitrate = AtomicInteger(initialBitrate)
    private var lastAdjustmentTime = System.currentTimeMillis()
    private val adjustmentCooldown = 2000L // 2秒冷却时间
    
    private var networkOptimizer: NetworkPerformanceOptimizer? = null
    
    init {
        context?.let {
            networkOptimizer = NetworkPerformanceOptimizer(it)
        }
        Log.i(TAG, "自适应码率控制器初始化: 初始码率=${initialBitrate/1000}kbps")
    }
    
    /**
     * 获取当前推荐的码率
     */
    fun getCurrentBitrate(
        frameComplexity: Float = 0.5f, // 0.0-1.0，画面复杂度
        bufferLevel: Float = 0.5f,     // 0.0-1.0，缓冲区填充程度
        packetLossRate: Float = 0.0f   // 0.0-1.0，丢包率
    ): Int {
        val now = System.currentTimeMillis()
        if (now - lastAdjustmentTime < adjustmentCooldown) {
            return currentBitrate.get() // 冷却期内不调整
        }
        
        val current = currentBitrate.get()
        var newBitrate = current
        
        // 获取网络质量
        val networkQuality = networkOptimizer?.getNetworkQuality() ?: 0.7f
        
        // 基础调整因子
        var adjustmentFactor = 1.0f
        
        // 1. 网络质量影响
        adjustmentFactor *= getNetworkAdjustmentFactor(networkQuality)
        
        // 2. 画面复杂度影响
        adjustmentFactor *= getComplexityAdjustmentFactor(frameComplexity)
        
        // 3. 缓冲区影响
        adjustmentFactor *= getBufferAdjustmentFactor(bufferLevel)
        
        // 4. 丢包率影响
        adjustmentFactor *= getPacketLossAdjustmentFactor(packetLossRate)
        
        // 计算新码率
        newBitrate = (current * adjustmentFactor).toInt()
        
        // 限制码率范围
        newBitrate = newBitrate.coerceIn(minBitrate, maxBitrate)
        
        // 如果变化超过10%，才进行实际调整
        val changeRatio = newBitrate.toFloat() / current.toFloat()
        if (changeRatio < 0.9f || changeRatio > 1.1f) {
            currentBitrate.set(newBitrate)
            lastAdjustmentTime = now
            
            Log.i(TAG, "码率调整: ${current/1000}k -> ${newBitrate/1000}k " +
                "(网络质量: ${(networkQuality*100).toInt()}%, " +
                "复杂度: ${(frameComplexity*100).toInt()}%, " +
                "缓冲区: ${(bufferLevel*100).toInt()}%)")
        }
        
        return currentBitrate.get()
    }
    
    /**
     * 强制设置码率（用于手动控制）
     */
    fun setBitrate(bitrate: Int) {
        val clampedBitrate = bitrate.coerceIn(minBitrate, maxBitrate)
        currentBitrate.set(clampedBitrate)
        lastAdjustmentTime = System.currentTimeMillis()
        
        Log.i(TAG, "手动设置码率: ${clampedBitrate/1000}kbps")
    }
    
    /**
     * 重置为初始码率
     */
    fun reset() {
        currentBitrate.set(initialBitrate)
        lastAdjustmentTime = System.currentTimeMillis()
        
        Log.i(TAG, "重置码率: ${initialBitrate/1000}kbps")
    }
    
    /**
     * 获取码率调整建议
     */
    fun getAdjustmentSuggestions(): List<String> {
        val suggestions = mutableListOf<String>()
        val networkQuality = networkOptimizer?.getNetworkQuality() ?: 0.7f
        
        when {
            networkQuality >= NETWORK_EXCELLENT -> {
                suggestions.add("网络质量优秀，建议使用高码率模式")
                suggestions.add("可启用H.265编码以获得最佳画质")
            }
            networkQuality >= NETWORK_GOOD -> {
                suggestions.add("网络质量良好，保持当前码率")
                suggestions.add("建议启用自适应码率控制")
            }
            networkQuality >= NETWORK_FAIR -> {
                suggestions.add("网络质量一般，建议适当降低码率")
                suggestions.add("可考虑降低帧率以减少带宽占用")
            }
            else -> {
                suggestions.add("网络质量较差，建议使用低码率模式")
                suggestions.add("建议启用丢帧策略和缓冲区优化")
            }
        }
        
        return suggestions
    }
    
    private fun getNetworkAdjustmentFactor(networkQuality: Float): Float {
        return when {
            networkQuality >= NETWORK_EXCELLENT -> 1.2f  // 优秀网络：提高20%
            networkQuality >= NETWORK_GOOD -> 1.0f       // 良好网络：保持原码率
            networkQuality >= NETWORK_FAIR -> 0.8f       // 一般网络：降低20%
            networkQuality >= NETWORK_POOR -> 0.6f       // 较差网络：降低40%
            else -> 0.4f                                // 极差网络：降低60%
        }
    }
    
    private fun getComplexityAdjustmentFactor(complexity: Float): Float {
        // 画面越复杂，需要的码率越高
        return 0.7f + 0.6f * complexity // 0.7x - 1.3x
    }
    
    private fun getBufferAdjustmentFactor(bufferLevel: Float): Float {
        return when {
            bufferLevel > BUFFER_HIGH -> 0.8f  // 缓冲区满：降低20%
            bufferLevel < BUFFER_LOW -> 1.2f  // 缓冲区空：提高20%
            else -> 1.0f                       // 正常范围：保持
        }
    }
    
    private fun getPacketLossAdjustmentFactor(packetLossRate: Float): Float {
        return when {
            packetLossRate > 0.1f -> 0.7f  // 高丢包率：降低30%
            packetLossRate > 0.05f -> 0.9f // 中等丢包率：降低10%
            else -> 1.0f                   // 低丢包率：保持
        }
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): AdaptiveBitrateStats {
        return AdaptiveBitrateStats(
            currentBitrate = currentBitrate.get(),
            minBitrate = minBitrate,
            maxBitrate = maxBitrate,
            networkQuality = networkOptimizer?.getNetworkQuality() ?: 0.0f,
            adjustmentCount = 0 // 可以添加调整计数统计
        )
    }
    
    data class AdaptiveBitrateStats(
        val currentBitrate: Int,
        val minBitrate: Int,
        val maxBitrate: Int,
        val networkQuality: Float,
        val adjustmentCount: Int
    )
}