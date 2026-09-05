package tools.alamobile.mod.util

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块进程崩溃自捕：把未捕获异常的堆栈写进 filesDir/ala_tool_crash.log。
 *
 * 背景（用户日志实证 2026-09-05）：模块 App 闪退时本文件里没有任何 [E/] 日志——
 * 未捕获异常不经过 [Logger]（它只记录主动调用），堆栈只存在于系统 logcat 的
 * crash buffer，小白用户拿不到。装上本 handler 后，崩溃堆栈落盘到模块进程
 * filesDir，用户下次打开 App 正常"导出日志"，[LogExporter] 会把它合并为
 * "模块进程崩溃记录"段——诊断闭环不要求用户会 adb。
 *
 * 设计要点：
 * - **无条件写入**：不查 [Logger.isEnabled]。崩溃是诊断的最后手段，若受日志
 *   开关控制，"logEnabled=false 时崩溃"永远抓不到现场。
 * - **链式转发**：写完必须调 previous handler（Android 默认 handler 负责弹
 *   崩溃对话框 + 杀进程 + 上报系统），吞掉会破坏系统崩溃报告甚至卡死进程。
 * - **独立文件**：不写进 ala_tool.log——崩溃可能就发生在 Logger 写入路径上
 *   （例如磁盘满），同文件会互相污染；独立文件 + [LogExporter] 合并最稳。
 * - **体积上限**：超 [MAX_LOG_SIZE] 截断保留后半（与 Logger 同策略）。
 */
object CrashCatcher {

    private const val TAG = "AlaMobileTool"
    private const val CRASH_FILE_NAME = "ala_tool_crash.log"
    private const val MAX_LOG_SIZE = 512 * 1024L  // 512KB

    private val mutex = Object()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 安装崩溃捕获（模块进程 Application.onCreate 调用，幂等无害）。
     *
     * 只包一层：写文件 + 委托 previous。previous 为 null（理论不会，
     * Android 运行时总有默认 handler）时兜底 killProcess。
     */
    fun install(context: Context) {
        AppLogDir.set(context.filesDir)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashCatcherHandler) return  // 已装过
        Thread.setDefaultUncaughtExceptionHandler(CrashCatcherHandler(previous))
    }

    private class CrashCatcherHandler(private val previous: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                writeCrash(thread, throwable)
            } catch (_: Throwable) {
                // 崩溃路径上的任何失败都不能阻塞委托——吞掉
            }
            previous?.uncaughtException(thread, throwable)
                ?: Process.killProcess(Process.myPid())
        }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val dir = AppLogDir.get() ?: return
        val file = File(dir, CRASH_FILE_NAME)

        synchronized(mutex) {
            try {
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    truncateFile(file)
                }
                val ts = timestampFormat.format(Date())
                // 首行带 Java 时间戳（与 Logger 同格式）：LogExporter.filterRecent
                // 按条目过滤，堆栈续行跟随首行一起保留/丢弃。
                val sb = StringBuilder()
                sb.append("$ts pid=${Process.myPid()} tid=${Process.myTid()} [E/Crash] uncaught on thread \"${thread.name}\"\n")
                sb.append(Log.getStackTraceString(throwable))
                // cause 链兜底（getStackTraceString 已含 Caused by，此处只防包裹异常丢了 root cause）
                var cause = throwable.cause
                while (cause != null) {
                    sb.append("Caused-by-chain: ${cause.javaClass.name}: ${cause.message}\n")
                    cause = cause.cause
                }
                sb.append('\n')
                file.appendText(sb.toString())
            } catch (_: Throwable) {
                // 磁盘满等写入失败：崩溃处理不能反噬，吞掉
            }
        }
    }

    private fun truncateFile(file: File) {
        try {
            val text = file.readText()
            val keepFrom = text.length / 2
            val cutPoint = text.indexOf('\n', keepFrom).let { if (it < 0) keepFrom else it + 1 }
            file.writeText(text.substring(cutPoint))
        } catch (_: Throwable) {
            // 截断失败就忽略，下一行继续追加
        }
    }
}

/** AppLogDir：Logger.logDir 是 private，这里独立解析，避免崩溃路径耦合 Logger 内部状态。 */
private object AppLogDir {
    @Volatile private var dir: File? = null
    fun set(d: File) { dir = d }
    fun get(): File? = dir
}
