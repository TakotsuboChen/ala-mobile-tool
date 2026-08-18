package tools.alamobile.mod.util

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志工具：logcat 始终输出，文件写入受 [enabled] 开关控制。
 *
 * **两个进程各自写各自的日志文件**：
 * - 模块进程（ConfigActivity）：`filesDir/ala_tool.log`
 * - 游戏进程（AlaMobileModule）：`externalFilesDir/ala_tool.log`
 *
 * 导出时由 [LogExporter] 通过 `createPackageContext` 合并两个文件。
 *
 * logcat 不受 [enabled] 控制——adb 调试时始终能看到日志。
 * 文件写入受 [enabled] 控制——`logEnabled=false` 时不写文件，避免占存储。
 *
 * 线程安全：[writeToFile] 用 synchronized 保护，多线程并发写不会交错。
 * 文件滚动：超 [MAX_LOG_SIZE]（2MB）时截断保留后半部分，防无限增长。
 */
object Logger {

    private const val TAG = "AlaMobileTool"
    private const val LOG_FILE_NAME = "ala_tool.log"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L  // 2MB

    private var logDir: File? = null
    private var enabled: Boolean = false
    private val mutex = Object()

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 初始化日志目录。
     *
     * @param context 进程 Context
     * @param isModuleProcess true=模块进程（用 filesDir），false=游戏进程（用 externalFilesDir）
     */
    fun init(context: Context, isModuleProcess: Boolean) {
        logDir = if (isModuleProcess) {
            context.filesDir
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(mutex) { this.enabled = enabled }
    }

    fun isEnabled(): Boolean = synchronized(mutex) { enabled }

    /**
     * 核心日志方法：同时打 logcat +（如果 enabled）写文件。
     *
     * 保留 [AlaMobileModule.logX] 的签名兼容性：
     * `logX(priority, tag, msg)` → `Logger.log(priority, tag, msg)`。
     */
    fun log(priority: Int, tag: String, msg: String) {
        android.util.Log.println(priority, tag, msg)
        if (!isEnabled()) return
        writeToFile(priority, tag, msg)
    }

    fun i(msg: String) = log(Log.INFO, TAG, msg)
    fun w(msg: String) = log(Log.WARN, TAG, msg)
    fun e(msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg: ${Log.getStackTraceString(t)}" else msg
        log(Log.ERROR, TAG, full)
    }

    /**
     * 写一行带时间戳 + pid + tid 的日志到文件。
     * 超 MAX_LOG_SIZE 时截断保留后半部分（简单滚动）。
     */
    private fun writeToFile(priority: Int, tag: String, msg: String) {
        val dir = logDir ?: return
        val file = File(dir, LOG_FILE_NAME)

        synchronized(mutex) {
            try {
                // 滚动检查：超 size 截断保留后半
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    truncateFile(file)
                }

                val prioStr = when (priority) {
                    Log.VERBOSE -> "V"
                    Log.DEBUG -> "D"
                    Log.INFO -> "I"
                    Log.WARN -> "W"
                    Log.ERROR -> "E"
                    else -> "?"
                }
                val ts = timestampFormat.format(Date())
                val line = "$ts pid=${Process.myPid()} tid=${Process.myTid()} [$prioStr/$tag] $msg\n"
                file.appendText(line)
            } catch (_: Throwable) {
                // 文件写入失败不影响功能
            }
        }
    }

    /**
     * 截断文件保留后半部分：读全部内容，丢弃前半，写回后半。
     * 简单但够用——日志不是高频操作，2MB 也不会太频繁触发。
     */
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