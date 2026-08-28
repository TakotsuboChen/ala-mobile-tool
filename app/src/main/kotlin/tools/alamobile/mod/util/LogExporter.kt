package tools.alamobile.mod.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 日志导出工具：收集模块进程 + 游戏进程的 Java/native 日志，
 * 合并到 cacheDir/logs/ 下，用 FileProvider 生成 URI 供 ShareSheet 分享。
 *
 * 跨进程读取策略（Android 11+ scoped storage）：
 * - 模块进程日志：直接读 filesDir（同进程天然可读）
 * - 游戏进程日志：通过 ConfigProvider IPC 读缓存（游戏进程在收到配置广播时
 *   把日志内容推到模块进程 cacheDir），因为 scoped storage 禁止跨包读
 *   Android/data/<other_pkg>/files/
 */
object LogExporter {

    private const val MODULE_LOG_FILE = "ala_tool.log"
    private const val GAME_LOG_FILE = "ala_tool.log"
    private const val NATIVE_LOG_FILE = "ala_tool_native.log"

    // 导出只保留最近 24h 的条目。日志文件是 append 累积的（跨会话滚动保留），
    // 全量导出会把几天前的旧会话全部带上（实测一份导出里 68% 是已修复版本的
    // proxy_shift 洪水），既撑大体积又淹没最近会话的现场。
    private const val RETENTION_MS = 24 * 60 * 60 * 1000L

    // 两种行首时间戳：Java "2026-08-28 21:54:08.291"、native "[2026-08-28T21:54:08.290"。
    private val TS_PREFIX = Regex("^(?:\\[)?(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2}:\\d{2}\\.\\d{3})")

    // 复用 ModConfig 的游戏包名常量（官版 + 共存版）
    private val GAME_PACKAGES = setOf(
        "com.Vince.AlamobileFormula",
        "com.Takotsubo.AlamobileFormula"
    )

    /**
     * 请求游戏进程重新推送最新日志，轮询等待缓存文件更新后返回。
     *
     * 日志推送是"推"模式（游戏进程 → 模块进程），只在两个时机推送：
     * 1) 15s 延迟末尾 2) ConfigReceiver 收到配置更新/REQUEST_LOGS 时。
     * 用户点"导出日志"时可能距上次推送已过很久，BillingManager.Awake() 等
     * 后续 hook 日志已产生但未推送。这里发 REQUEST_LOGS 广播触发 ConfigReceiver
     * 重新推送最新日志文件，然后轮询等待缓存文件更新。
     *
     * 同步策略：记录发送广播前的缓存文件 lastModified，发广播后每 200ms 检查一次，
     * 缓存文件更新了（说明新日志分片已拼接收齐）就立即继续；超时 [timeoutMs]
     *（默认 10 秒）就放弃，用旧缓存导出。游戏没运行时广播无人接收，直接超时用旧缓存。
     */
    private suspend fun requestFreshLogs(context: Context, timeoutMs: Long = 10000) {
        try {
            val cachedJava = File(context.cacheDir, "game_java.log")
            val cachedNative = File(context.cacheDir, "game_native.log")
            val oldJavaTime = cachedJava.lastModified()
            val oldNativeTime = cachedNative.lastModified()

            // 发 REQUEST_LOGS 广播让游戏进程 ConfigReceiver 重新推送最新日志。
            // ConfigReceiver 是动态注册的（运行在游戏进程），用 setPackage 定向
            // 到游戏包——与配置更新广播同理，模块进程通过 <queries> 声明可见游戏包。
            for (pkg in GAME_PACKAGES) {
                val intent = Intent("tools.alamobile.mod.REQUEST_LOGS")
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                context.sendBroadcast(intent)
                android.util.Log.i("AlaMobileTool", "LogExporter: sent REQUEST_LOGS to $pkg")
            }

            // 轮询等待缓存文件更新——用 delay 挂起不阻塞主线程。
            // 分片广播从游戏进程回到模块进程的延迟不固定（flyme 后台调度），
            // 固定 sleep 可能太短（用了旧缓存）或太长（用户等太久）。
            val pollInterval = 200L
            val deadline = System.currentTimeMillis() + timeoutMs
            var waited = 0L
            while (System.currentTimeMillis() < deadline) {
                delay(pollInterval)
                waited += pollInterval
                val newJavaTime = cachedJava.lastModified()
                val newNativeTime = cachedNative.lastModified()
                // 两个文件都更新了（或游戏没运行两者都不存在）→ 完成
                val javaUpdated = newJavaTime > oldJavaTime || !cachedJava.exists()
                val nativeUpdated = newNativeTime > oldNativeTime || !cachedNative.exists()
                if (javaUpdated && nativeUpdated) {
                    android.util.Log.i("AlaMobileTool", "LogExporter: fresh logs received after ${waited}ms (java=${cachedJava.length()} native=${cachedNative.length()})")
                    return
                }
            }
            android.util.Log.w("AlaMobileTool", "LogExporter: REQUEST_LOGS timed out after ${waited}ms, using cached logs")
        } catch (e: Throwable) {
            android.util.Log.w("AlaMobileTool", "LogExporter: requestFreshLogs failed: ${e.message}")
        }
    }

    /**
     * 收集所有日志文件，合并到一个文件，返回 FileProvider URI。
     *
     * 读取策略（Android 11+ scoped storage 兼容）：
     * - 模块进程日志：直接读 context.filesDir（同进程，天然可读）
     * - 游戏进程日志：
     *   - 策略 1：ConfigProvider IPC（NPatch 下游戏进程推送的缓存）
     *   - 策略 2：createPackageContext 读游戏 externalFilesDir（某些设备可行）
     *   - 策略 3：直接读绝对路径 /sdcard/Android/data/<pkg>/files/（root/某些设备可行）
     *
     * 如果所有策略都失败，返回的 URI 对应的 txt 里会包含提示信息，
     * 告诉用户日志文件的实际位置（可用 root 文件管理器或 adb 读取）。
     *
     * @return URI 供分享；null 表示没有任何可导出的日志
     */
    suspend fun export(context: Context): Uri? {
        // 先请求游戏进程推送最新日志（如果游戏在运行），等待广播往返。
        requestFreshLogs(context)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.cacheDir, "logs/ala_tool_log_$timestamp.txt")
        outFile.parentFile?.mkdirs()

        val sb = StringBuilder()
        var foundAny = false

        // 1. 模块进程日志（filesDir/ala_tool.log）
        val moduleLog = File(context.filesDir, MODULE_LOG_FILE)
        if (moduleLog.exists()) {
            appendLogFile(sb, "=== 模块进程日志 (Java) ===", moduleLog)
            if (sb.isNotBlank()) foundAny = true
        }

        // 2. 游戏进程日志：多策略读取
        var gameJavaLog: String? = null
        var gameNativeLog: String? = null

        // 策略 1：读 cacheDir 里的缓存（LogReceiver 非定向广播写入，LSPosed + NPatch 通用）
        val cachedJavaLog = File(context.cacheDir, "game_java.log")
        val cachedNativeLog = File(context.cacheDir, "game_native.log")
        if (cachedJavaLog.exists()) {
            gameJavaLog = try { cachedJavaLog.readText() } catch (_: Throwable) { null }
            android.util.Log.d("AlaMobileTool", "LogExporter: cache game_java.log ${gameJavaLog?.length ?: 0} bytes")
        }
        if (cachedNativeLog.exists()) {
            gameNativeLog = try { cachedNativeLog.readText() } catch (_: Throwable) { null }
            android.util.Log.d("AlaMobileTool", "LogExporter: cache game_native.log ${gameNativeLog?.length ?: 0} bytes")
        }

        // 策略 2 & 3：直接读游戏进程的 externalFilesDir
        if (gameJavaLog == null && gameNativeLog == null) {
            for (pkg in GAME_PACKAGES) {
                if (gameJavaLog != null && gameNativeLog != null) break
                try {
                    val gameCtx = context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
                    val extDir = gameCtx.getExternalFilesDir(null)
                    if (extDir != null) {
                        if (gameJavaLog == null) {
                            val f = File(extDir, GAME_LOG_FILE)
                            if (f.exists()) gameJavaLog = try { f.readText() } catch (_: Throwable) { null }
                        }
                        if (gameNativeLog == null) {
                            val f = File(extDir, NATIVE_LOG_FILE)
                            if (f.exists()) gameNativeLog = try { f.readText() } catch (_: Throwable) { null }
                        }
                    }
                } catch (_: Throwable) { }
                // 策略 3：绝对路径
                if (gameJavaLog == null) {
                    val f = File("/sdcard/Android/data/$pkg/files/$GAME_LOG_FILE")
                    if (f.exists()) gameJavaLog = try { f.readText() } catch (_: Throwable) { null }
                }
                if (gameNativeLog == null) {
                    val f = File("/sdcard/Android/data/$pkg/files/$NATIVE_LOG_FILE")
                    if (f.exists()) gameNativeLog = try { f.readText() } catch (_: Throwable) { null }
                }
            }
        }

        if (gameJavaLog != null) {
            sb.append("=== 游戏进程日志 (Java) ===\n").append(filterRecent(gameJavaLog)).append('\n')
            foundAny = true
        }
        if (gameNativeLog != null) {
            sb.append("=== 游戏进程日志 (Native) ===\n").append(filterRecent(gameNativeLog)).append('\n')
            foundAny = true
        }

        if (!foundAny) {
            // 所有策略都失败：生成提示信息而非返回 null
            android.util.Log.w("AlaMobileTool", "LogExporter: all strategies failed, generating hint")
            sb.append("未找到日志文件。\n\n")
            sb.append("可能原因：\n")
            sb.append("1. 日志开关未打开（设置 → 启用日志）\n")
            sb.append("2. 游戏未运行过（日志在游戏运行时产生）\n")
            sb.append("3. LSPosed 模式下跨进程读取受限\n\n")
            sb.append("日志文件位置（可用 root 文件管理器或 adb pull 读取）：\n")
            for (pkg in GAME_PACKAGES) {
                sb.append("  /sdcard/Android/data/$pkg/files/ala_tool.log\n")
                sb.append("  /sdcard/Android/data/$pkg/files/ala_tool_native.log\n")
            }
        }

        outFile.writeText(sb.toString())
        android.util.Log.i("AlaMobileTool", "LogExporter: exported ${outFile.length()} bytes to ${outFile.absolutePath}")

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    }

    private fun appendLogFile(sb: StringBuilder, header: String, file: File) {
        if (!file.exists()) {
            android.util.Log.d("AlaMobileTool", "LogExporter: $header — file not found: ${file.absolutePath}")
            return
        }
        android.util.Log.d("AlaMobileTool", "LogExporter: $header — reading ${file.length()} bytes from ${file.absolutePath}")
        sb.append(header).append('\n')
        try {
            sb.append(filterRecent(file.readText()))
        } catch (e: Throwable) {
            android.util.Log.w("AlaMobileTool", "LogExporter: read failed: ${e.message}")
            sb.append("[读取失败: ${e.message}]\n")
        }
        sb.append('\n')
    }

    /**
     * 按条目过滤出最近 [RETENTION_MS] 内的日志。
     *
     * 带时间戳的行开启一个新条目；其后所有不带时间戳的续行（异常堆栈的
     * "at ..."、Caused by 等）跟随所属条目一起保留或丢弃——只按单行过滤
     * 会把堆栈从它的异常头切走，日志变碎片。段头、提示文本等无时间戳
     * 起始的行无条件保留。时间戳解析失败时该条目保留（宁可多带不可丢）。
     */
    private fun filterRecent(content: String): String {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        // SimpleDateFormat 非线程安全；导出低频，局部创建避免共享状态
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        val sb = StringBuilder(content.length)
        var keep = true
        for (line in content.lineSequence()) {
            val m = TS_PREFIX.find(line)
            if (m != null) {
                val (date, time) = m.destructured
                keep = try {
                    (fmt.parse("$date $time")?.time ?: Long.MAX_VALUE) >= cutoff
                } catch (_: Throwable) {
                    true
                }
            }
            if (keep) sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /**
     * 调起系统分享面板。
     */
    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享日志"))
    }
}