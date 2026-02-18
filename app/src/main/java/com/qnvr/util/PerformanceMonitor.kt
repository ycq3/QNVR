package com.qnvr.util

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.io.RandomAccessFile

class PerformanceMonitor(context: Context) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val stats = PerformanceStats()
    private val listeners = mutableListOf<(PerformanceStats) -> Unit>()
    private var isRunning = false

    data class PerformanceStats(
        var cpuUsagePercent: Float = 0f,
        var memoryUsageMb: Float = 0f,
        var totalMemoryMb: Float = 0f,
        var frameRate: Float = 0f,
        var encodedBitrateKbps: Float = 0f,
        var encodedFrameCount: Long = 0,
        var timestamp: Long = System.currentTimeMillis()
    )

    fun start(intervalMs: Long = 1000) {
        if (isRunning) return
        isRunning = true
        
        handlerThread = HandlerThread("PerformanceMonitor").apply { start() }
        handler = Handler(handlerThread!!.looper)
        updateStats()
        scheduleUpdate(intervalMs)
    }

    fun stop() {
        isRunning = false
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    fun addListener(listener: (PerformanceStats) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (PerformanceStats) -> Unit) {
        listeners.remove(listener)
    }

    fun getStats(): PerformanceStats = stats.copy()

    fun recordEncodedFrame(sizeBytes: Int) {
        synchronized(stats) {
            stats.encodedFrameCount++
        }
    }

    private fun scheduleUpdate(intervalMs: Long) {
        handler?.postDelayed({
            if (isRunning) {
                updateStats()
                scheduleUpdate(intervalMs)
            }
        }, intervalMs)
    }

    private fun updateStats() {
        val newStats = stats.copy()
        newStats.timestamp = System.currentTimeMillis()
        
        newStats.memoryUsageMb = getMemoryUsageMb()
        newStats.totalMemoryMb = getTotalMemoryMb()
        newStats.cpuUsagePercent = getCpuUsagePercent()
        
        synchronized(stats) {
            stats.cpuUsagePercent = newStats.cpuUsagePercent
            stats.memoryUsageMb = newStats.memoryUsageMb
            stats.totalMemoryMb = newStats.totalMemoryMb
            stats.timestamp = newStats.timestamp
        }
        
        listeners.forEach { it(newStats) }
    }

    private fun getMemoryUsageMb(): Float {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val debugMemInfo = ActivityManager.MemoryInfo()
        val pid = Process.myPid()
        val pids = intArrayOf(pid)
        val memInfos = activityManager.getProcessMemoryInfo(pids)
        
        return if (memInfos.isNotEmpty()) {
            (memInfos[0].totalPss / 1024.0).toFloat()
        } else {
            0f
        }
    }

    private fun getTotalMemoryMb(): Float {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024.0 * 1024.0)).toFloat()
    }

    private var lastCpuTime: Long = 0
    private var lastAppCpuTime: Long = 0

    private fun getCpuUsagePercent(): Float {
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val load = reader.readLine()
            val toks = load.split(" ").filter { it.isNotEmpty() }
            
            val idle = toks[5].toLong()
            val total = toks.subList(1, toks.size).sumOf { it.toLong() }
            
            val appCpuTime = getAppCpuTime()
            
            val cpuUsage = if (lastCpuTime > 0 && total > lastCpuTime) {
                val totalDelta = total - lastCpuTime
                val appDelta = appCpuTime - lastAppCpuTime
                (appDelta.toFloat() / totalDelta.toFloat()) * 100f
            } else {
                0f
            }
            
            lastCpuTime = total
            lastAppCpuTime = appCpuTime
            
            reader.close()
            return cpuUsage.coerceIn(0f, 100f)
        } catch (e: Exception) {
            return 0f
        }
    }

    private fun getAppCpuTime(): Long {
        try {
            val pid = Process.myPid()
            val reader = RandomAccessFile("/proc/$pid/stat", "r")
            val line = reader.readLine()
            val toks = line.split(" ")
            reader.close()
            
            val utime = toks[13].toLong()
            val stime = toks[14].toLong()
            return utime + stime
        } catch (e: Exception) {
            return 0
        }
    }
}
