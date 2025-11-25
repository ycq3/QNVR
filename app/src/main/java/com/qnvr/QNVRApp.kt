package com.qnvr

import android.app.Application
import io.sentry.android.core.SentryAndroid

class QNVRApp : Application() {
  override fun onCreate() {
    super.onCreate()
    SentryAndroid.init(this) { o ->
      o.dsn = BuildConfig.SENTRY_DSN
      o.setDebug(BuildConfig.DEBUG)
      o.getLogs().setEnabled(true)
      o.environment = if (BuildConfig.DEBUG) "debug" else "production"
      o.release = "com.pipiqiang.qnvr@" + BuildConfig.VERSION_NAME
      o.tracesSampleRate = 0.2
    }
  }
}
