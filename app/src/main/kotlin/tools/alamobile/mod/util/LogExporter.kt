package tools.alamobile.mod.util

import tools.alamobile.mod.util.Logger
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
     * 请求游戏进程推送最新日志并**等待 3 秒**：cacheDir 缓存文件（game_java.log /
     * game_native.log，LogReceiver 广播分片拼装写入）mtime 在窗口内更新 = 游戏
     * 推来了新日志 = 游戏此刻活着。超时无更新返回 false——调用方据此判定游戏
     * 未运行并 Toast 提示，**禁止回落导出旧缓存**（2026-09-08 用户定案：不允
     * 许用陈旧日志冒充本次导出）。
     *
     * ⚠️ 已知边界（实机实证 2026-09-07）：部分 ROM 对后台应用的跨应用定向
     * 广播限流——游戏活着但送不到动态注册的 ConfigReceiver，此探针会误判
     * "未运行"。实机（ColorOS 系）实测「游戏前台玩着 → 切到模块导出」往返
     * 正常时广播可达；若遇到误判，属 ROM 行为，提示文案已引导用户
     * "启动游戏并至少等待 15 秒，再返回此处导出"。
     *
     * @return true = 3s 内收到游戏推送（继续导出）；false = 超时（不导出）
     */
    suspend fun awaitFreshLogs(context: Context, timeoutMs: Long = 3000): Boolean {
        return try {
            val cachedJava = File(context.cacheDir, "game_java.log")
            val cachedNative = File(context.cacheDir, "game_native.log")
            val oldJavaTime = cachedJava.lastModified()
            val oldNativeTime = cachedNative.lastModified()

            for (pkg in GAME_PACKAGES) {
                val intent = Intent("tools.alamobile.mod.REQUEST_LOGS")
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                context.sendBroadcast(intent)
                Logger.i("AlaMobileTool", "LogExporter: sent REQUEST_LOGS to $pkg")
            }

            val pollInterval = 150L
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                delay(pollInterval)
                val javaUpdated = cachedJava.lastModified() > oldJavaTime
                val nativeUpdated = cachedNative.lastModified() > oldNativeTime
                if (javaUpdated || nativeUpdated) {
                    // 任一文件更新即活体证据（推送是 java+native 成对发，
                    // 先到的分片先落盘）。等一小会让成对的另一个文件也到。
                    Logger.i("AlaMobileTool", "LogExporter: fresh logs arriving after ${System.currentTimeMillis() - (deadline - timeoutMs)}ms, waiting briefly for pairing")
                    delay(500)
                    return true
                }
            }
            Logger.i("AlaMobileTool", "LogExporter: no fresh logs within ${timeoutMs}ms → game not running, export denied")
            false
        } catch (e: Throwable) {
            Logger.w("AlaMobileTool", "LogExporter: awaitFreshLogs failed: ${e.message}")
            false
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
        // 游戏进程日志新鲜度已由调用方 awaitFreshLogs 门控（3s 内没等到游戏
        // 推送就不走到这里）——此处直接读缓存文件（刚被游戏推送更新过）。

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.cacheDir, "logs/ala_tool_log_$timestamp.txt")
        outFile.parentFile?.mkdirs()

        val sb = StringBuilder()
        var foundAny = false

        // 0. 崩溃记录（filesDir/ala_tool_crash.log）——放最前，诊断价值最高。
        // CrashCatcher 无条件落盘，可能记录到旧版本 logEnabled=false 时段的
        // 崩溃，不能走 filterRecent（时间窗外也必须保留），全量带出。
        val crashLog = File(context.filesDir, "ala_tool_crash.log")
        if (crashLog.exists() && crashLog.length() > 0) {
            sb.append("=== 模块进程崩溃记录 ===\n")
            try {
                sb.append(crashLog.readText())
            } catch (e: Throwable) {
                sb.append("[读取失败: ${e.message}]\n")
            }
            sb.append('\n')
            foundAny = true
        }

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
            Logger.d("AlaMobileTool", "LogExporter: cache game_java.log ${gameJavaLog?.length ?: 0} bytes")
        }
        if (cachedNativeLog.exists()) {
            gameNativeLog = try { cachedNativeLog.readText() } catch (_: Throwable) { null }
            Logger.d("AlaMobileTool", "LogExporter: cache game_native.log ${gameNativeLog?.length ?: 0} bytes")
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

        // 游戏进程 native 崩溃记录（ala_tool_crash_native.log）——CrashCatcher
        // native 侧信号级落盘，与 Java crash 段同策略：不走 filterRecent，
        // 时间窗外也必须保留，全量带出。读取顺序：缓存（推送链）→ 直接路径。
        var nativeCrashLog: String? = null
        val cachedNativeCrash = File(context.cacheDir, "game_native_crash.log")
        if (cachedNativeCrash.exists()) {
            nativeCrashLog = try { cachedNativeCrash.readText() } catch (_: Throwable) { null }
        }
        if (nativeCrashLog == null) {
            for (pkg in GAME_PACKAGES) {
                val f = File("/sdcard/Android/data/$pkg/files/ala_tool_crash_native.log")
                if (f.exists() && f.length() > 0) {
                    nativeCrashLog = try { f.readText() } catch (_: Throwable) { null }
                    if (nativeCrashLog != null) break
                }
            }
        }
        if (nativeCrashLog != null && nativeCrashLog!!.isNotBlank()) {
            sb.append("=== 游戏进程崩溃记录 ===\n").append(nativeCrashLog).append('\n')
            foundAny = true
        }

        if (!foundAny) {
            // 所有策略都失败：生成提示信息而非返回 null
            Logger.w("AlaMobileTool", "LogExporter: all strategies failed, generating hint")
            sb.append("未找到日志文件。\n\n")
            sb.append("可能原因：\n")
            sb.append("1. 游戏未运行过（日志在游戏运行时产生）\n")
            sb.append("2. LSPosed 模式下跨进程读取受限\n\n")
            sb.append("日志文件位置（可用 root 文件管理器或 adb pull 读取）：\n")
            for (pkg in GAME_PACKAGES) {
                sb.append("  /sdcard/Android/data/$pkg/files/ala_tool.log\n")
                sb.append("  /sdcard/Android/data/$pkg/files/ala_tool_native.log\n")
            }
        }

        outFile.writeText(sb.toString())
        Logger.i("AlaMobileTool", "LogExporter: exported ${outFile.length()} bytes to ${outFile.absolutePath}")

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    }

    private fun appendLogFile(sb: StringBuilder, header: String, file: File) {
        if (!file.exists()) {
            Logger.d("AlaMobileTool", "LogExporter: $header — file not found: ${file.absolutePath}")
            return
        }
        Logger.d("AlaMobileTool", "LogExporter: $header — reading ${file.length()} bytes from ${file.absolutePath}")
        sb.append(header).append('\n')
        try {
            sb.append(filterRecent(file.readText()))
        } catch (e: Throwable) {
            Logger.w("AlaMobileTool", "LogExporter: read failed: ${e.message}")
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