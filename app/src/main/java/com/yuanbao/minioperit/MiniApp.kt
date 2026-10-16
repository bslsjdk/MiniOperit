package com.yuanbao.minioperit

import android.app.Application
import com.yuanbao.minioperit.backend.BackendRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 在这里注册你自己的推理后端：
 *   BackendRegistry.register(MyHexagonBackend())
 *   BackendRegistry.setActive("myhex")
 */
class MiniApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    /**
     * 崩溃不再只体现在"打不开"。任何未捕获异常都会写到：
     *   Android/data/com.yuanbao.minioperit/files/crash/crash-<时间>.txt
     * 用任意文件管理器就能看到完整堆栈，方便定位。
     */
    private fun installCrashHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(getExternalFilesDir(null), "crash").apply { mkdirs() }
                val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date()) + ".txt"
                val sb = StringBuilder()
                sb.appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                sb.appendLine("线程: ${thread.name}")
                sb.appendLine("异常: ${throwable.javaClass.name}: ${throwable.message}")
                sb.appendLine()
                sb.appendLine("---- 堆栈 ----")
                throwable.stackTrace.forEach { sb.appendLine("  $it") }
                throwable.cause?.let { c ->
                    sb.appendLine()
                    sb.appendLine("---- cause ----")
                    sb.appendLine("${c.javaClass.name}: ${c.message}")
                    c.stackTrace.forEach { sb.appendLine("  $it") }
                }
                File(dir, name).writeText(sb.toString())
            }
            default?.uncaughtException(thread, throwable)
        }
    }
}
