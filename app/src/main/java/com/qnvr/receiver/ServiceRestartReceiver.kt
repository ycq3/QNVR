package com.qnvr.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.qnvr.service.RecorderService
import com.qnvr.util.SettingsManager

class ServiceRestartReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RESTART_SERVICE = "com.qnvr.action.RESTART_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RESTART_SERVICE) {
            if (SettingsManager.isBootStartEnabled(context)) {
                val serviceIntent = Intent(context, RecorderService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
