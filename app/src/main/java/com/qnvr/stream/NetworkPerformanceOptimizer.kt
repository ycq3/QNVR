package com.qnvr.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.util.Log
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * 网络性能优化器
 * 实现网络质量监测、自适应传输和缓冲区优化
 */
class NetworkPerformanceOptimizer(private val context: Context) {
    
    companion object {
        private const val TAG = "NetworkOptimizer"
        
        // 网络质量阈值
        private const val EXCELLENT_NETWORK = 0.9f
        private const val GOOD_NETWORK = 0.7f
        private const val FAIR_NETWORK = 0.5f
        private const val POOR_NETWORK = 0.3f
    }
    
    private val lastBytesTransferred = AtomicLong(0)
    private val lastBytesReceived = AtomicLong(0)
    private val lastCheckTime = AtomicLong(System.currentTimeMillis())
    
    /**
     * 获取当前网络质量评分（0.0-1.0）
     */
    fun getNetworkQuality(): Float {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return 0.1f
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return 0.1f
            
            var qualityScore = 0.5f // 基础分数
            
            // 网络类型评分
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    qualityScore = 0.9f
                    Log.d(TAG, "网络类型: WiFi")
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    qualityScore = 0.6f
                    Log.d(TAG, "网络类型: 移动网络")
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    qualityScore = 0.95f
                    Log.d(TAG, "网络类型: 以太网")
                }
                else -> {
                    qualityScore = 0.3f
                    Log.d(TAG, "网络类型: 其他")
                }
            }
            
            // 带宽估计
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                capabilities.linkDownstreamBandwidthKbps?.let { downstream ->
                    when {
                        downstream > 10000 -> qualityScore *= 1.1f // 10Mbps以上
                        downstream > 5000 -> qualityScore *= 1.0f  // 5-10Mbps
                        downstream > 2000 -> qualityScore *= 0.8f  // 2-5Mbps
                        downstream > 1000 -> qualityScore *= 0.6f  // 1-2Mbps
                        else -> qualityScore *= 0.4f               // 1Mbps以下
                    }
                    Log.d(TAG, "下行带宽: ${downstream}Kbps")
                }
            }
            
            // 信号强度（移动网络）
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                // 这里可以添加信号强度检测逻辑
                qualityScore *= 0.9f // 移动网络默认降低10%
            }
            
            qualityScore.coerceIn(0.1f, 1.0f)
            
        } catch (e: Exception) {
            Log.e(TAG, "获取网络质量失败", e)
            0.5f // 默认中等质量
        }
    }
    
    /**
     * 计算实时网络吞吐量（KB/s）
     */
    fun getCurrentThroughput(): Pair<Float, Float> {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = (currentTime - lastCheckTime.get()).coerceAtLeast(1000) // 至少1秒
        
        val currentTxBytes = TrafficStats.getTotalTxBytes()
        val currentRxBytes = TrafficStats.getTotalRxBytes()
        
        val lastTx = lastBytesTransferred.get()
        val lastRx = lastBytesReceived.get()
        
        val txThroughput = if (lastTx > 0) {
            ((currentTxBytes - lastTx) / elapsedTime.toFloat()) * 1000 / 1024 // KB/s
        } else 0f
        
        val rxThroughput = if (lastRx > 0) {
            ((currentRxBytes - lastRx) / elapsedTime.toFloat()) * 1000 / 1024 // KB/s
        } else 0f
        
        // 更新计数器
        lastBytesTransferred.set(currentTxBytes)
        lastBytesReceived.set(currentRxBytes)
        lastCheckTime.set(currentTime)
        
        Log.d(TAG, "网络吞吐量: 上行=${txThroughput.format(2)}KB/s, 下行=${rxThroughput.format(2)}KB/s")
        
        return Pair(txThroughput, rxThroughput)
    }
    
    /**
     * 根据网络质量推荐码率
     */
    fun getRecommendedBitrate(networkQuality: Float, baseBitrate: Int): Int {
        return when {
            networkQuality >= EXCELLENT_NETWORK -> (baseBitrate * 1.2).toInt() // 优秀网络：提高20%
            networkQuality >= GOOD_NETWORK -> baseBitrate                      // 良好网络：保持原码率
            networkQuality >= FAIR_NETWORK -> (baseBitrate * 0.7).toInt()      // 一般网络：降低30%
            networkQuality >= POOR_NETWORK -> (baseBitrate * 0.4).toInt()      // 较差网络：降低60%
            else -> (baseBitrate * 0.2).toInt()                                // 极差网络：降低80%
        }
    }
    
    /**
     * 优化Socket缓冲区大小
     */
    fun optimizeSocketBuffer(socket: Socket) {
        try {
            val networkQuality = getNetworkQuality()
            
            // 根据网络质量设置缓冲区大小
            val bufferSize = when {
                networkQuality >= EXCELLENT_NETWORK -> 128 * 1024 // 128KB
                networkQuality >= GOOD_NETWORK -> 64 * 1024       // 64KB
                networkQuality >= FAIR_NETWORK -> 32 * 1024       // 32KB
                else -> 16 * 1024                                 // 16KB
            }
            
            socket.sendBufferSize = bufferSize
            socket.receiveBufferSize = bufferSize
            
            Log.i(TAG, "Socket缓冲区优化: ${bufferSize / 1024}KB")
            
        } catch (e: Exception) {
            Log.w(TAG, "Socket缓冲区优化失败", e)
        }
    }
    
    /**
     * 检查网络延迟（ping测试）
     */
    suspend fun measureNetworkLatency(host: String): Long {
        return try {
            val startTime = System.currentTimeMillis()
            val address = InetAddress.getByName(host)
            val endTime = System.currentTimeMillis()
            
            val latency = endTime - startTime
            Log.d(TAG, "网络延迟($host): ${latency}ms")
            
            latency
        } catch (e: Exception) {
            Log.e(TAG, "测量网络延迟失败", e)
            1000L // 默认高延迟
        }
    }
    
    /**
     * 获取网络优化建议
     */
    fun getNetworkOptimizationSuggestions(networkQuality: Float): List<String> {
        val suggestions = mutableListOf<String>()
        
        when {
            networkQuality >= EXCELLENT_NETWORK -> {
                suggestions.add("网络质量优秀，可使用高码率模式")
                suggestions.add("建议启用H.265编码以获得最佳画质")
            }
            networkQuality >= GOOD_NETWORK -> {
                suggestions.add("网络质量良好，可保持标准码率")
                suggestions.add("建议启用自适应码率控制")
            }
            networkQuality >= FAIR_NETWORK -> {
                suggestions.add("网络质量一般，建议降低码率")
                suggestions.add("建议使用H.264编码以提高兼容性")
            }
            else -> {
                suggestions.add("网络质量较差，强烈建议使用低码率模式")
                suggestions.add("建议启用帧率自适应和丢帧策略")
                suggestions.add("考虑使用子码流进行预览")
            }
        }
        
        return suggestions
    }
    
    private fun Float.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }
}