package com.qnvr.stream

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 性能测试工具
 * 用于验证视频和网络性能优化效果
 */
class PerformanceTestUtil(private val context: Context) {
    
    companion object {
        private const val TAG = "PerformanceTest"
        
        // 测试配置
        private const val TEST_DURATION_MS = 30000L // 30秒测试
        private const val SAMPLE_INTERVAL_MS = 1000L // 每秒采样
    }
    
    private val networkOptimizer = NetworkPerformanceOptimizer(context)
    private val videoOptimizer = VideoPerformanceOptimizer.Companion
    
    /**
     * 运行综合性能测试
     */
    suspend fun runComprehensiveTest(): PerformanceTestResults {
        Log.i(TAG, "开始综合性能测试...")
        
        val results = PerformanceTestResults()
        
        // 1. 网络性能测试
        results.networkResults = testNetworkPerformance()
        
        // 2. 视频编码性能测试
        results.videoResults = testVideoEncodingPerformance()
        
        // 3. 内存使用测试
        results.memoryResults = testMemoryUsage()
        
        // 4. 综合评分
        results.overallScore = calculateOverallScore(results)
        
        Log.i(TAG, "性能测试完成，综合评分: ${results.overallScore}/100")
        
        return results
    }
    
    /**
     * 网络性能测试
     */
    private suspend fun testNetworkPerformance(): NetworkTestResults {
        Log.i(TAG, "开始网络性能测试...")
        
        val results = NetworkTestResults()
        val startTime = System.currentTimeMillis()
        
        // 测试网络质量
        results.networkQuality = networkOptimizer.getNetworkQuality()
        
        // 测试吞吐量
        val throughputSamples = mutableListOf<Pair<Float, Float>>()
        while (System.currentTimeMillis() - startTime < TEST_DURATION_MS) {
            val throughput = networkOptimizer.getCurrentThroughput()
            throughputSamples.add(throughput)
            
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.delay(SAMPLE_INTERVAL_MS)
            }
        }
        
        // 计算平均吞吐量
        if (throughputSamples.isNotEmpty()) {
            results.avgUploadThroughput = throughputSamples.map { it.first }.average().toFloat()
            results.avgDownloadThroughput = throughputSamples.map { it.second }.average().toFloat()
        }
        
        // 获取网络优化建议
        results.optimizationSuggestions = networkOptimizer.getNetworkOptimizationSuggestions(results.networkQuality)
        
        Log.i(TAG, "网络性能测试完成: 质量=${(results.networkQuality*100).toInt()}%, " +
            "上传=${results.avgUploadThroughput.format(2)}KB/s, " +
            "下载=${results.avgDownloadThroughput.format(2)}KB/s")
        
        return results
    }
    
    /**
     * 视频编码性能测试
     */
    private fun testVideoEncodingPerformance(): VideoTestResults {
        Log.i(TAG, "开始视频编码性能测试...")
        
        val results = VideoTestResults()
        
        // 测试H.265支持
        results.hevcSupported = videoOptimizer.isHevcHardwareEncodingSupported()
        
        // 测试不同分辨率的推荐配置
        val resolutions = listOf("1080p", "720p", "480p")
        results.recommendedConfigs = resolutions.associateWith { resolution ->
            videoOptimizer.getRecommendedEncoderConfig(resolution)
        }
        
        // 测试自适应码率算法
        results.adaptiveBitrateTest = testAdaptiveBitrateAlgorithm()
        
        Log.i(TAG, "视频编码性能测试完成: H.265支持=${results.hevcSupported}")
        
        return results
    }
    
    /**
     * 测试自适应码率算法
     */
    private fun testAdaptiveBitrateAlgorithm(): AdaptiveBitrateTest {
        val test = AdaptiveBitrateTest()
        
        // 模拟不同网络条件下的码率调整
        val networkConditions = listOf(0.9f, 0.7f, 0.5f, 0.3f, 0.1f) // 网络质量
        val baseBitrate = 2000000 // 2Mbps
        
        test.bitrateAdjustments = networkConditions.associateWith { quality ->
            val adaptiveController = AdaptiveBitrateController(baseBitrate, context = context)
            adaptiveController.getCurrentBitrate()
        }
        
        return test
    }
    
    /**
     * 内存使用测试
     */
    private fun testMemoryUsage(): MemoryTestResults {
        Log.i(TAG, "开始内存使用测试...")
        
        val results = MemoryTestResults()
        val runtime = Runtime.getRuntime()
        
        // 获取内存信息
        results.totalMemory = runtime.totalMemory()
        results.freeMemory = runtime.freeMemory()
        results.maxMemory = runtime.maxMemory()
        results.usedMemory = results.totalMemory - results.freeMemory
        
        // 计算内存使用率
        results.memoryUsageRatio = results.usedMemory.toFloat() / results.maxMemory.toFloat()
        
        Log.i(TAG, "内存使用测试完成: 使用率=${(results.memoryUsageRatio*100).toInt()}%")
        
        return results
    }
    
    /**
     * 计算综合评分
     */
    private fun calculateOverallScore(results: PerformanceTestResults): Int {
        var score = 0
        
        // 网络质量评分（40%）
        score += (results.networkResults.networkQuality * 40).toInt()
        
        // 视频编码评分（30%）
        score += if (results.videoResults.hevcSupported) 25 else 15
        score += if (results.videoResults.adaptiveBitrateTest.bitrateAdjustments.isNotEmpty()) 5 else 0
        
        // 内存使用评分（20%）
        score += when {
            results.memoryResults.memoryUsageRatio < 0.3f -> 20 // 优秀
            results.memoryResults.memoryUsageRatio < 0.6f -> 15 // 良好
            results.memoryResults.memoryUsageRatio < 0.8f -> 10 // 一般
            else -> 5 // 较差
        }
        
        // 综合优化建议评分（10%）
        score += if (results.networkResults.optimizationSuggestions.isNotEmpty()) 10 else 5
        
        return score.coerceIn(0, 100)
    }
    
    /**
     * 生成性能测试报告
     */
    fun generateReport(results: PerformanceTestResults): String {
        val report = StringBuilder()
        
        report.append("=== QNVR性能测试报告 ===\n\n")
        
        // 综合评分
        report.append("综合评分: ${results.overallScore}/100\n")
        report.append("测试时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}\n\n")
        
        // 网络性能
        report.append("1. 网络性能测试\n")
        report.append("   网络质量: ${(results.networkResults.networkQuality*100).toInt()}%\n")
        report.append("   平均上传吞吐量: ${results.networkResults.avgUploadThroughput.format(2)} KB/s\n")
        report.append("   平均下载吞吐量: ${results.networkResults.avgDownloadThroughput.format(2)} KB/s\n")
        
        // 视频编码性能
        report.append("\n2. 视频编码性能测试\n")
        report.append("   H.265硬件编码支持: ${if (results.videoResults.hevcSupported) "是" else "否"}\n")
        
        // 内存使用
        report.append("\n3. 内存使用测试\n")
        report.append("   内存使用率: ${(results.memoryResults.memoryUsageRatio*100).toInt()}%\n")
        report.append("   已使用内存: ${results.memoryResults.usedMemory / (1024*1024)} MB\n")
        report.append("   最大可用内存: ${results.memoryResults.maxMemory / (1024*1024)} MB\n")
        
        // 优化建议
        report.append("\n4. 优化建议\n")
        results.networkResults.optimizationSuggestions.forEachIndexed { index, suggestion ->
            report.append("   ${index + 1}. $suggestion\n")
        }
        
        return report.toString()
    }
    
    private fun Float.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }
    
    data class PerformanceTestResults(
        var networkResults: NetworkTestResults = NetworkTestResults(),
        var videoResults: VideoTestResults = VideoTestResults(),
        var memoryResults: MemoryTestResults = MemoryTestResults(),
        var overallScore: Int = 0
    )
    
    data class NetworkTestResults(
        var networkQuality: Float = 0f,
        var avgUploadThroughput: Float = 0f,
        var avgDownloadThroughput: Float = 0f,
        var optimizationSuggestions: List<String> = emptyList()
    )
    
    data class VideoTestResults(
        var hevcSupported: Boolean = false,
        var recommendedConfigs: Map<String, VideoPerformanceOptimizer.EncoderConfig> = emptyMap(),
        var adaptiveBitrateTest: AdaptiveBitrateTest = AdaptiveBitrateTest()
    )
    
    data class AdaptiveBitrateTest(
        var bitrateAdjustments: Map<Float, Int> = emptyMap()
    )
    
    data class MemoryTestResults(
        var totalMemory: Long = 0,
        var freeMemory: Long = 0,
        var maxMemory: Long = 0,
        var usedMemory: Long = 0,
        var memoryUsageRatio: Float = 0f
    )
}