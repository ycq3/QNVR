package com.qnvr

import android.content.Context
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class StatsData(
    val currentFps: Int = 0,
    val cpuUsage: Float = 0f,
    val networkRxBytes: Long = 0L,
    val networkTxBytes: Long = 0L,
    val networkRxKbps: Float = 0f,
    val networkTxKbps: Float = 0f,
    val encoderName: String = "",
    val encoderType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int = 0,
    val cpuTemp: Float = 0f,
    val batteryTemp: Float = 0f,
    val batteryLevel: Int = 0
)

class StatsMonitor(private val context: Context) {
    private val frameCount = AtomicInteger(0)
    private val lastFpsTime = AtomicLong(System.currentTimeMillis())
    private var currentFps = 0

    private var lastCpuTime = 0L
    private var lastAppCpuTime = 0L
    private var cpuUsage = 0f

    private var lastNetworkTime = System.currentTimeMillis()
    private var lastRxBytes = TrafficStats.getUidRxBytes(Process.myUid())
    private var lastTxBytes = TrafficStats.getUidTxBytes(Process.myUid())
    private var networkRxKbps = 0f
    private var networkTxKbps = 0f

    private var encoderName = ""
    private var encoderType = ""
    private var width = 0
    private var height = 0
    private var bitrate = 0

    private var cpuTemp = 0f
    private var batteryTemp = 0f
    private var batteryLevel = 0

    init {
        startMonitoring()
    }

    fun onFrame() {
        frameCount.incrementAndGet()
    }

    fun setEncoderInfo(name: String, type: String, w: Int, h: Int, br: Int) {
        encoderName = name
        encoderType = type
        width = w
        height = h
        bitrate = br
    }

    private fun startMonitoring() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(1000)
                    updateStats()
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private fun updateStats() {
        updateFps()
        updateCpuUsage()
        updateNetworkUsage()
        updateCpuTemp()
        updateBatteryInfo()
    }

    private fun updateFps() {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastFpsTime.get()) / 1000.0
        if (elapsed >= 1.0) {
            currentFps = (frameCount.get() / elapsed).toInt()
            frameCount.set(0)
            lastFpsTime.set(now)
        }
    }

    private fun updateCpuUsage() {
        try {
            // 获取当前应用的CPU时间
            val appTime = getAppCpuTime()
            val now = System.currentTimeMillis()

            if (lastCpuTime > 0 && lastAppCpuTime > 0) {
                val timeDelta = now - lastCpuTime
                val appDelta = appTime - lastAppCpuTime
                
                if (timeDelta > 0) {
                    // 计算应用占用的CPU百分比
                    // appDelta 是 jiffies，需要转换为毫秒
                    // 通常 1 jiffy = 10ms (100Hz)，但也可能是 1ms。
                    // 我们可以通过 sysconf(_SC_CLK_TCK) 获取，但在 Java 中比较麻烦。
                    // 大多数 Android 设备是 100Hz (10ms)。
                    // 更加准确的方法是读取 /proc/uptime 获取系统启动时间，但这也很复杂。
                    // 简单的估算：appDelta (jiffies) * 10ms / timeDelta (ms) * 100%
                    // 或者更稳妥地：既然无法精确获取系统总CPU时间，我们只计算 App 的相对使用率
                    // 假设 100Hz
                    val appTimeMs = appDelta * 10 // 10ms per jiffy estimate
                    
                    // 考虑到多核，这个值可能超过 100%。
                    // 我们可以除以核心数来归一化，或者直接显示总占用。
                    // 用户通常习惯看到 0-100% (单核) 或 0-800% (8核)。
                    // 这里我们尝试归一化到 Runtime.getRuntime().availableProcessors()
                    
                    val cores = Runtime.getRuntime().availableProcessors()
                    val usage = (appTimeMs.toFloat() / timeDelta.toFloat()) * 100f / cores
                    
                    // 限制在 0-100% 之间，避免异常值
                    cpuUsage = usage.coerceIn(0f, 100f)
                }
            }

            lastCpuTime = now
            lastAppCpuTime = appTime
        } catch (e: Exception) {
            android.util.Log.e("StatsMonitor", "Error reading CPU stats", e)
        }
    }

    private fun getAppCpuTime(): Long {
        return try {
            val pid = Process.myPid()
            val reader = RandomAccessFile("/proc/$pid/stat", "r")
            val line = reader.readLine()
            reader.close()

            // Find the last ')' to skip the process name which might contain spaces
            val lastParen = line.lastIndexOf(')')
            if (lastParen == -1) return 0L
            
            val content = line.substring(lastParen + 2) // Skip ") "
            val parts = content.split(" ")
            
            // utime is 14th field in stat, so it's index 13.
            // But we skipped name and pid (indices 0 and 1).
            // Original: pid (name) state ppid ...
            // We skipped "pid (name) ".
            // So "state" is index 0 in parts.
            // "state" is field 3 in original (1-based).
            // utime is field 14.
            // So 14 - 3 = 11. parts[11] is utime.
            // stime is field 15. parts[12].
            // cutime is field 16. parts[13].
            // cstime is field 17. parts[14].
            
            if (parts.size >= 15) {
                val utime = parts[11].toLong()
                val stime = parts[12].toLong()
                val cutime = parts[13].toLong()
                val cstime = parts[14].toLong()
                utime + stime + cutime + cstime
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun updateNetworkUsage() {
        try {
            val now = System.currentTimeMillis()
            val elapsedSeconds = (now - lastNetworkTime) / 1000.0

            if (elapsedSeconds >= 1.0) {
                val currentRx = TrafficStats.getUidRxBytes(Process.myUid())
                val currentTx = TrafficStats.getUidTxBytes(Process.myUid())
                
                // 如果 TrafficStats 返回 -1，说明不支持
                if (currentRx != TrafficStats.UNSUPPORTED.toLong() && currentTx != TrafficStats.UNSUPPORTED.toLong()) {
                    val rxDelta = currentRx - lastRxBytes
                    val txDelta = currentTx - lastTxBytes

                    if (rxDelta >= 0 && txDelta >= 0) {
                        networkRxKbps = (rxDelta * 8.0 / elapsedSeconds / 1000.0).toFloat()
                        networkTxKbps = (txDelta * 8.0 / elapsedSeconds / 1000.0).toFloat()
                    }

                    lastRxBytes = currentRx
                    lastTxBytes = currentTx
                    lastNetworkTime = now
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("StatsMonitor", "Error reading network stats", e)
        }
    }

    private fun updateCpuTemp() {
        cpuTemp = try {
            val tempFiles = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/class/thermal/thermal_zone2/temp",
                "/sys/class/hwmon/hwmon0/temp1_input",
                "/sys/class/hwmon/hwmon1/temp1_input"
            )
            var maxTemp = 0f
            for (path in tempFiles) {
                try {
                    val reader = RandomAccessFile(path, "r")
                    val line = reader.readLine()
                    val temp = line?.toFloatOrNull()
                    reader.close()
                    if (temp != null) {
                        val celsius = if (temp > 1000) temp / 1000f else temp
                        if (celsius > maxTemp && celsius > 0) {
                            maxTemp = celsius
                        }
                    }
                } catch (_: Exception) {
                }
            }
            maxTemp
        } catch (e: Exception) {
            0f
        }
    }

    private fun updateBatteryInfo() {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            val intentFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val batteryIntent = context.registerReceiver(null, intentFilter)
            batteryIntent?.let {
                val temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                batteryTemp = temp / 10.0f
            }
        } catch (e: Exception) {
            android.util.Log.e("StatsMonitor", "Error reading battery info", e)
        }
    }

    fun getStats(): StatsData {
        return StatsData(
            currentFps = currentFps,
            cpuUsage = cpuUsage,
            networkRxBytes = lastRxBytes,
            networkTxBytes = lastTxBytes,
            networkRxKbps = networkRxKbps,
            networkTxKbps = networkTxKbps,
            encoderName = encoderName,
            encoderType = encoderType,
            width = width,
            height = height,
            bitrate = bitrate,
            cpuTemp = cpuTemp,
            batteryTemp = batteryTemp,
            batteryLevel = batteryLevel
        )
    }
}
