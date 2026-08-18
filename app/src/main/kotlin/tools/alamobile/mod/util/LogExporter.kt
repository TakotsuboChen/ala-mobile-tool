package tools.alamobile.mod.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // 复用 ModConfig 的游戏包名常量（官版 + 共存版）
    private val GAME_PACKAGES = setOf(
        "com.Vince.AlamobileFormula",
        "com.Takotsubo.AlamobileFormula"
    )

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
    fun export(context: Context): Uri? {
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
            sb.append("=== 游戏进程日志 (Java) ===\n").append(gameJavaLog).append('\n')
            foundAny = true
        }
        if (gameNativeLog != null) {
            sb.append("=== 游戏进程日志 (Native) ===\n").append(gameNativeLog).append('\n')
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
            sb.append(file.readText())
        } catch (e: Throwable) {
            android.util.Log.w("AlaMobileTool", "LogExporter: read failed: ${e.message}")
            sb.append("[读取失败: ${e.message}]\n")
        }
        sb.append('\n')
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