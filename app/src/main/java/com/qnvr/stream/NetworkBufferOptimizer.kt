package com.qnvr.stream

import android.util.Log
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 网络缓冲区优化器
 * 实现智能缓冲区管理和传输优化
 */
class NetworkBufferOptimizer {
    
    companion object {
        private const val TAG = "BufferOptimizer"
        
        // 缓冲区大小配置（字节）
        private const val BUFFER_SMALL = 8 * 1024    // 8KB
        private const val BUFFER_MEDIUM = 32 * 1024  // 32KB
        private const val BUFFER_LARGE = 128 * 1024  // 128KB
        
        // 网络质量阈值
        private const val NETWORK_EXCELLENT = 0.8f
        private const val NETWORK_GOOD = 0.6f
        private const val NETWORK_FAIR = 0.4f
    }
    
    private val totalBytesSent = AtomicLong(0)
    private val totalBytesReceived = AtomicLong(0)
    private val packetLossCount = AtomicInteger(0)
    private val retransmitCount = AtomicInteger(0)
    
    /**
     * 根据网络质量优化Socket缓冲区
     */
    fun optimizeSocketBuffers(socket: Socket, networkQuality: Float) {
        try {
            val bufferSize = when {
                networkQuality >= NETWORK_EXCELLENT -> BUFFER_LARGE
                networkQuality >= NETWORK_GOOD -> BUFFER_MEDIUM
                networkQuality >= NETWORK_FAIR -> BUFFER_MEDIUM
                else -> BUFFER_SMALL
            }
            
            socket.sendBufferSize = bufferSize
            socket.receiveBufferSize = bufferSize
            socket.tcpNoDelay = true // 禁用Nagle算法，减少延迟
            
            Log.i(TAG, "Socket缓冲区优化: 发送=${socket.sendBufferSize/1024}KB, " +
                "接收=${socket.receiveBufferSize/1024}KB, " +
                "TCP_NODELAY=${socket.tcpNoDelay}")
            
        } catch (e: Exception) {
            Log.w(TAG, "Socket缓冲区优化失败", e)
        }
    }
    
    /**
     * 智能数据发送（包含拥塞控制）
     */
    fun sendDataWithCongestionControl(
        outputStream: java.io.OutputStream,
        data: ByteArray,
        networkQuality: Float
    ): Boolean {
        return try {
            // 根据网络质量调整发送策略
            when {
                networkQuality >= NETWORK_EXCELLENT -> {
                    // 优秀网络：直接发送
                    outputStream.write(data)
                    outputStream.flush()
                }
                networkQuality >= NETWORK_GOOD -> {
                    // 良好网络：分块发送
                    sendInChunks(outputStream, data, 4 * 1024) // 4KB分块
                }
                networkQuality >= NETWORK_FAIR -> {
                    // 一般网络：小分块发送
                    sendInChunks(outputStream, data, 2 * 1024) // 2KB分块
                }
                else -> {
                    // 较差网络：更小的分块和延迟控制
                    sendInChunksWithDelay(outputStream, data, 1 * 1024, 10L) // 1KB分块，10ms延迟
                }
            }
            
            totalBytesSent.addAndGet(data.size.toLong())
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "数据发送失败", e)
            packetLossCount.incrementAndGet()
            false
        }
    }
    
    /**
     * 分块发送数据
     */
    private fun sendInChunks(
        outputStream: java.io.OutputStream,
        data: ByteArray,
        chunkSize: Int
    ) {
        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, minOf(offset + chunkSize, data.size))
            outputStream.write(chunk)
            offset += chunkSize
        }
        outputStream.flush()
    }
    
    /**
     * 带延迟的分块发送（用于较差网络）
     */
    private fun sendInChunksWithDelay(
        outputStream: java.io.OutputStream,
        data: ByteArray,
        chunkSize: Int,
        delayMs: Long
    ) {
        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, minOf(offset + chunkSize, data.size))
            outputStream.write(chunk)
            outputStream.flush()
            
            offset += chunkSize
            
            // 在分块之间添加延迟以减少网络拥塞
            if (offset < data.size) {
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }
    
    /**
     * 自适应重传策略
     */
    fun shouldRetransmit(
        packetLossRate: Float,
        networkQuality: Float,
        retryCount: Int
    ): Boolean {
        // 最大重试次数
        if (retryCount >= 3) return false
        
        // 根据网络质量和丢包率决定是否重传
        return when {
            networkQuality >= NETWORK_EXCELLENT -> packetLossRate > 0.01f // 优秀网络：丢包率>1%时重传
            networkQuality >= NETWORK_GOOD -> packetLossRate > 0.05f      // 良好网络：丢包率>5%时重传
            networkQuality >= NETWORK_FAIR -> packetLossRate > 0.1f       // 一般网络：丢包率>10%时重传
            else -> packetLossRate > 0.2f                                // 较差网络：丢包率>20%时重传
        }
    }
    
    /**
     * 计算推荐的缓冲区大小
     */
    fun calculateOptimalBufferSize(
        bitrate: Int, // 比特率（bps）
        latency: Long, // 网络延迟（ms）
        networkQuality: Float
    ): Int {
        // 基础缓冲区大小 = 比特率 × 延迟 / 8
        val baseBufferSize = (bitrate * latency / 1000 / 8).toInt()
        
        // 根据网络质量调整
        val qualityFactor = when {
            networkQuality >= NETWORK_EXCELLENT -> 1.0f
            networkQuality >= NETWORK_GOOD -> 1.5f
            networkQuality >= NETWORK_FAIR -> 2.0f
            else -> 3.0f
        }
        
        val optimalSize = (baseBufferSize * qualityFactor).toInt()
        
        // 限制在合理范围内
        return optimalSize.coerceIn(BUFFER_SMALL, BUFFER_LARGE)
    }
    
    /**
     * 获取传输统计信息
     */
    fun getTransmissionStats(): TransmissionStats {
        return TransmissionStats(
            totalBytesSent = totalBytesSent.get(),
            totalBytesReceived = totalBytesReceived.get(),
            packetLossCount = packetLossCount.get(),
            retransmitCount = retransmitCount.get(),
            packetLossRate = if (totalBytesSent.get() > 0) {
                packetLossCount.get().toFloat() / (totalBytesSent.get() / 1024).toFloat()
            } else 0f
        )
    }
    
    /**
     * 重置统计信息
     */
    fun resetStats() {
        totalBytesSent.set(0)
        totalBytesReceived.set(0)
        packetLossCount.set(0)
        retransmitCount.set(0)
    }
    
    /**
     * 记录数据接收
     */
    fun recordDataReceived(bytes: Int) {
        totalBytesReceived.addAndGet(bytes.toLong())
    }
    
    /**
     * 记录重传
     */
    fun recordRetransmission() {
        retransmitCount.incrementAndGet()
    }
    
    data class TransmissionStats(
        val totalBytesSent: Long,
        val totalBytesReceived: Long,
        val packetLossCount: Int,
        val retransmitCount: Int,
        val packetLossRate: Float
    )
}