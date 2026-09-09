package tools.alamobile.mod.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志工具：logcat + 文件写入无条件开启。
 *
 * **两个进程各自写各自的日志文件**：
 * - 模块进程（ConfigActivity）：`filesDir/ala_tool.log`
 * - 游戏进程（AlaMobileModule）：`externalFilesDir/ala_tool.log`
 *
 * 导出时由 [LogExporter] 通过 `createPackageContext` 合并两个文件。
 *
 * 历史说明：曾有 logEnabled 开关控制文件写入（2026-09-06 移除）。排查游戏进程
 * 闪退时发现关日志 = 丢现场，且用户不会主动开日志——诊断数据必须默认全量，
 * 2MB 滚动上限已足够控制存储占用。
 *
 * 线程安全：[writeToFile] 只在单写线程（ala-logger HandlerThread）执行，
 * synchronized 仅为滚动窗口内的互斥。文件行序 = 调用序（单写者串行）。
 * 文件滚动：超 [MAX_LOG_SIZE]（2MB）时截断保留后半部分，防无限增长。
 */
object Logger {

    private const val TAG = "AlaMobileTool"
    private const val LOG_FILE_NAME = "ala_tool.log"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L  // 2MB

    private var logDir: File? = null
    private val mutex = Object()

    // 文件写入线程（懒创建，init 后首次入队时起）。HandlerThread 单写者：
    // 串行 append 保行序；调用方（含主线程）只做入队，永不阻塞。
    // 主线程 Handler 场景下 quit 会丢队列尾日志——Logger 是进程级单例
    // 无生命周期终点，不 quit，进程退出由系统回收。
    @Volatile private var writeHandler: Handler? = null
    private val writeThreadLock = Any()

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun enqueueWrite(priority: Int, tag: String, msg: String) {
        val handler = writeHandler ?: synchronized(writeThreadLock) {
            writeHandler ?: HandlerThread("ala-logger").apply {
                start()
            }.let { Handler(it.looper) }.also { writeHandler = it }
        }
        handler.post { writeToFile(priority, tag, msg) }
    }

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

    /**
     * 核心日志方法：同时打 logcat + 写文件。
     *
     * logcat（Log.println，内核环形缓冲）同步执行——崩溃现场最后几条日志
     * 必须在进程死前进 logcat。文件写入经单线程 HandlerThread 异步落盘
     * （2026-09-09 卡顿排查）：旧实现主线程逐条 open/write/close FUSE 文件，
     * 触摸诊断日志随 MOVE 事件率放大时成为游戏进程主线程热点（反馈用户
     * 设备踏板开启才卡的主因之一）。异步化后主线程成本 = 格式化 + 入队。
     *
     * 顺序保证：单写线程 + append 串行，文件内行序与调用序一致。
     * 崩溃丢尾：进程被杀时队列中未落盘的日志会丢（最多积压若干行）——
     * logcat 侧仍有完整现场（见上），导出日志以文件为主时接受此边界。
     *
     * 保留 [AlaMobileModule.logX] 的签名兼容性：
     * `logX(priority, tag, msg)` → `Logger.log(priority, tag, msg)`。
     */
    fun log(priority: Int, tag: String, msg: String) {
        android.util.Log.println(priority, tag, msg)
        enqueueWrite(priority, tag, msg)
    }

    fun i(msg: String) = log(Log.INFO, TAG, msg)
    fun w(msg: String) = log(Log.WARN, TAG, msg)
    fun e(msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg: ${Log.getStackTraceString(t)}" else msg
        log(Log.ERROR, TAG, full)
    }

    // ── 带 tag 的重载：供直用 android.util.Log 的调用点迁移（2026-09-07 收编，
    // 全部日志必须双写 logcat+文件，见 CLAUDE.md「日志红线」）。签名对齐
    // android.util.Log.x(tag, msg)，机械替换 Log.x( → Logger.x( 即可。
    fun v(tag: String, msg: String) = log(Log.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(Log.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Log.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Log.ERROR, tag, msg)
    fun w(tag: String, msg: String, t: Throwable) = log(Log.WARN, tag, "$msg: ${Log.getStackTraceString(t)}")
    fun e(tag: String, msg: String, t: Throwable) = log(Log.ERROR, tag, "$msg: ${Log.getStackTraceString(t)}")

    /**
     * 写一行带时间戳 + pid + tid 的日志到文件。
     * 超 MAX_LOG_SIZE 时截断保留后半部分（简单滚动）。
     *
     * 只在写线程执行；时间戳在此处取（真实落盘时刻）——若在调用线程取，
     * 队列积压时文件内时间序仍正确（单写线程按序处理），保持现状即可。
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