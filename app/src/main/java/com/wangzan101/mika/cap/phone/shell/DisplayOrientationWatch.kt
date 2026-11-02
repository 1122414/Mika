package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/** 只在有虚拟屏时监听任务方向、窗口大小变化;不读应用内容,不改变任务焦点。 */
@SuppressLint("PrivateApi")
internal class DisplayOrientationWatch(private val taskManager: Any, private val sync: () -> Unit) {
    private val thread = HandlerThread("mika-display-orientation").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    private val scan = Runnable { if (!closed) runCatching(sync).onFailure { Log.w("MikaShell", "display orientation scan failed", it) } }
    private val signal = object : Binder() {
        init { attachInterface(null, "android.app.ITaskStackListener") }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                if (!closed) {
                    handler.removeCallbacks(scan)
                    handler.postDelayed(scan, 50)
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
    private val listener = Class.forName("android.app.ITaskStackListener\$Stub")
        .getMethod("asInterface", IBinder::class.java).invoke(null, signal)
    private val listening = runCatching {
        taskManager.javaClass.methods.first { it.name == "registerTaskStackListener" }.invoke(taskManager, listener)
    }.isSuccess
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            scan.run()
            handler.postDelayed(this, 500)
        }
    }

    init { if (listening) handler.post(scan) else handler.post(poll) }

    fun close() {
        closed = true
        if (listening) runCatching {
            taskManager.javaClass.methods.first { it.name == "unregisterTaskStackListener" }.invoke(taskManager, listener)
        }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
