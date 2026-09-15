package tools.alamobile.mod.util

import tools.alamobile.mod.util.Logger
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出工具：收集模块进程 + 游戏进程的 Java/native 日志，
 * 合并到 cacheDir/logs/ 下，用 FileProvider 生成 URI 供 ShareSheet 分享。
 * 另附 NPatch 框架日志（同目录 `npatch/log/`）——覆盖「模块根本没加载起来」的
 * 开屏闪退现场，见 [npatchLogFiles]。
 *
 * 读取策略：**模块 App 持 AFA（MANAGE_EXTERNAL_STORAGE）直接跨包读**
 * `/sdcard/Android/media/<游戏包>/`（2026-09-14 定案）。游戏进程把
 * ala_tool.log / ala_tool_native.log / ala_tool_crash_native.log 全部
 * 写在这个目录（见 [Logger.init] / native_log.c / crash_hook.c）。
 *
 * **为什么不再走"广播推送 + 等待新鲜日志"**（旧链路已删除）：
 * 旧实现靠 `awaitFreshLogs` 给游戏进程发 REQUEST_LOGS 广播、等 3s 看 cacheDir
 * 缓存 mtime 是否更新，超时即判定"游戏未运行"拒绝导出。该门控在**最需要
 * 日志的场景恰好失效**：游戏闪退/无法正常运行时没有进程能响应广播，用户
 * 反而导不出崩溃现场。media 直读链无此依赖——导出只读文件，与游戏是否在
 * 运行、广播是否可达完全无关。
 *
 * AFA 由 [tools.alamobile.mod.ui.PermissionGateScreen] 强制授权（未授权
 * 无法进入主界面），故导出路径不另做权限判断；读不到即产提示信息。
 */
object LogExporter {

    private const val MODULE_LOG_FILE = "ala_tool.log"
    private const val GAME_LOG_FILE = "ala_tool.log"
    private const val NATIVE_LOG_FILE = "ala_tool_native.log"
    private const val NATIVE_CRASH_FILE = "ala_tool_crash_native.log"
    private const val MODULE_CRASH_FILE = "ala_tool_crash.log"

    // NPatch 本地模式的框架日志：`<gameMediaDir>/npatch/log/<yyyyMMdd>.log`。
    // 它记录的是**模块加载之前**的启动期事件（cache 命中/重建、LoadedApk source
    // 模式、崩溃栈），是「游戏开屏闪退但模块日志全空」场景的唯一现场——
    // 模块导出只读自己那两个 log 文件，此前这类问题必须向用户讨要第二份文件。
    // 目录可能多天累积，只取最新几个（列表本身也是对外证据，见下）。
    private const val NPATCH_LOG_DIR = "npatch/log"
    private const val NPATCH_LOG_MAX_FILES = 3
    private const val NPATCH_MARKER = "] Loaded patch config"

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

    /** 游戏包日志目录：`/sdcard/Android/media/<pkg>/`（模块 App 持 AFA 跨包直读）。 */
    private fun gameMediaDir(pkg: String): File =
        File(Environment.getExternalStorageDirectory(), "Android/media/$pkg")

    /**
     * 收集所有日志文件，合并到一个文件，返回 FileProvider URI。
     *
     * 读取来源：
     * - 模块进程日志：直接读 context.filesDir（同进程）
     * - 游戏进程日志：`/sdcard/Android/media/<游戏包>/`（AFA 跨包直读）
     *
     * 读不到任何日志时，返回的 txt 里包含提示信息与文件位置。
     *
     * @return URI 供分享；null 表示没有任何可导出的日志
     */
    suspend fun export(context: Context): Uri? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.cacheDir, "logs/ala_tool_log_$timestamp.txt")
        outFile.parentFile?.mkdirs()

        val sb = StringBuilder()
        var foundAny = false

        // 0. 模块进程崩溃记录（filesDir/ala_tool_crash.log）——放最前，诊断价值最高。
        // CrashCatcher 无条件落盘，可能记录到旧版本 logEnabled=false 时段的
        // 崩溃，不能走 filterRecent（时间窗外也必须保留），全量带出。
        val moduleCrash = File(context.filesDir, MODULE_CRASH_FILE)
        if (moduleCrash.exists() && moduleCrash.length() > 0) {
            sb.append("=== 模块进程崩溃记录 ===\n")
            try {
                sb.append(moduleCrash.readText())
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
            foundAny = true
        }

        // 2. 游戏进程日志：/sdcard/Android/media/<游戏包>/（AFA 跨包直读）。
        // 两个包都试（用户可能装官版或共存版），取第一个读到的。
        var gameJavaLog: String? = null
        var gameNativeLog: String? = null
        var nativeCrashLog: String? = null
        for (pkg in GAME_PACKAGES) {
            val dir = gameMediaDir(pkg)
            if (!dir.exists()) continue
            if (gameJavaLog == null) {
                val f = File(dir, GAME_LOG_FILE)
                if (f.exists()) gameJavaLog = try { f.readText() } catch (_: Throwable) { null }
            }
            if (gameNativeLog == null) {
                val f = File(dir, NATIVE_LOG_FILE)
                if (f.exists()) gameNativeLog = try { f.readText() } catch (_: Throwable) { null }
            }
            if (nativeCrashLog == null) {
                val f = File(dir, NATIVE_CRASH_FILE)
                if (f.exists() && f.length() > 0) {
                    nativeCrashLog = try { f.readText() } catch (_: Throwable) { null }
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

        // 游戏进程 native 崩溃记录（ala_tool_crash_native.log）——crash_hook.c
        // 信号级落盘，与 Java crash 段同策略：不走 filterRecent，时间窗外也必须
        // 保留，全量带出。
        if (nativeCrashLog != null && nativeCrashLog.isNotBlank()) {
            sb.append("=== 游戏进程崩溃记录 ===\n").append(nativeCrashLog).append('\n')
            foundAny = true
        }

        // 3. NPatch 框架日志——「模块加载之前」的启动期现场。放在自家日志之后：
        // 模块正常工作时它多是噪声；只有开屏闪退（游戏进程从未跑到模块初始化、
        // 上面各段为空）时它才是唯一证据。两个包都试。
        for (pkg in GAME_PACKAGES) {
            val files = npatchLogFiles(pkg)
            if (files.isEmpty()) continue
            // 先列出文件名——「最近有没有新日志」本身是对外证据（例：游戏卡死时
            // crash_hook 无信号、无高层异常，只有断流时间线；见 LAP_HOOK_NOTES 类场景）
            sb.append("=== NPatch 框架日志 (${files.size} 个文件) ===\n")
            Logger.d("AlaMobileTool", "LogExporter: NPatch 段 $pkg 命中 ${files.size} 个: " +
                    files.joinToString { it.name })
            for (f in files) {
                sb.append("  【${f.name}】\n")
                try {
                    // NPatch 行首形如 `[2026-09-15T16:25:13.576][pkg:pid;tid]`，与
                    // TS_PREFIX 兼容；崩溃例外栈是普通续行，会跟随其所属条目。
                    val text = filterRecent(f.readText())
                    sb.append(text)
                    Logger.d("AlaMobileTool",
                            "LogExporter: NPatch ${f.name} — ${text.length} 字符")
                } catch (e: Throwable) {
                    Logger.w("AlaMobileTool", "LogExporter: NPatch ${f.name} 读取失败: ${e.message}")
                    sb.append("[读取失败: ${e.message}]\n")
                }
                sb.append('\n')
            }
            foundAny = true
        }

        if (!foundAny) {
            // 所有来源都没读到：生成提示信息而非返回 null
            Logger.w("AlaMobileTool", "LogExporter: no log files found")
            sb.append("未找到日志文件。\n\n")
            sb.append("可能原因：\n")
            sb.append("1. 游戏从未运行过（日志在游戏运行时产生）\n")
            sb.append("2. 模块未获「所有文件访问」权限（无法读取游戏日志目录）\n\n")
            sb.append("日志文件位置：\n")
            for (pkg in GAME_PACKAGES) {
                sb.append("  /sdcard/Android/media/$pkg/ala_tool.log\n")
                sb.append("  /sdcard/Android/media/$pkg/ala_tool_native.log\n")
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

    /**
     * NPatch 框架日志文件：`<gameMediaDir>/npatch/log/` 下的 `*.log`，按 mtime 降序、最多
     * [NPATCH_LOG_MAX_FILES] 个（目录跨天累积，全量会把几周前无关会话一起带上）。
     *
     * 排除任何内含 `Loaded patch config` 的文件——那是**本次启动成功导致模块真正
     * 运行**后写下的（该行由 LSPApplication 在模块装载阶段打印，不可能是崩溃现场）。
     * 排除后留下的即「有过启动但模块从未活下来」的会话，正是开屏闪退要的证据。
     */
    private fun npatchLogFiles(pkg: String): List<File> {
        val dir = File(gameMediaDir(pkg), NPATCH_LOG_DIR)
        val all = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".log") } ?: return emptyList()
        return all.sortedByDescending { it.lastModified() }
            .filterNot { containsTargetMarker(it) }
            .take(NPATCH_LOG_MAX_FILES)
    }

    private fun containsTargetMarker(file: File): Boolean = try {
        file.readLines().any { it.contains(NPATCH_MARKER) }
    } catch (_: Throwable) {
        false // 读不了（含恰好被游戏写入）就别排除，宁可多带
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
