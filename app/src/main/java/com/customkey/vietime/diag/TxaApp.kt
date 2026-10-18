package com.customkey.vietime.diag

import android.app.Activity
import android.os.Bundle
import com.google.android.play.core.splitcompat.SplitCompatApplication

/**
 * Application của TxTelex: cài bộ bắt sự cố (Java + native + nhịp tim) TRƯỚC mọi thứ khác, rồi
 * ở luồng nền chuyển dump native / ApplicationExitInfo của lần chạy trước thành báo cáo.
 * Kế thừa SplitCompatApplication để module on-demand (Dịch) nạp được lớp.
 */
class TxaApp : SplitCompatApplication() {
    override fun onCreate() {
        super.onCreate()
        TxaCrashHub.install(this)
        if (TxaCrashHub.isCrashProcess(this)) return
        Thread({ TxaLogger.guard("TxaApp", "ingest sự cố cũ", Unit) { TxaCrashHub.ingest(this) } }, "txa-ingest").start()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) = TxaLogger.crumb("Mở ${a.javaClass.simpleName}")
            override fun onActivityResumed(a: Activity) = TxaLogger.d("Lifecycle", "resume ${a.javaClass.simpleName}")
            override fun onActivityPaused(a: Activity) = TxaLogger.d("Lifecycle", "pause ${a.javaClass.simpleName}")
            override fun onActivityDestroyed(a: Activity) = TxaLogger.d("Lifecycle", "destroy ${a.javaClass.simpleName}")
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) TxaLogger.w("TxaApp", "Hệ thống báo thiếu RAM (trim level $level)")
    }
}
