package tools.alamobile.mod.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * 模块进程接收游戏进程推送的日志内容。
 *
 * 广播方向：游戏进程 → 模块进程（与 ConfigReceiver 反向）。
 * 游戏进程在 15s 延迟末尾或 ConfigReceiver 收到配置变更时，
 * 把自己的 ala_tool.log / ala_tool_native.log 内容通过定向广播发给模块进程。
 * 模块进程收到后写到 cacheDir，供 LogExporter 导出时读取。
 *
 * 定向广播 setPackage(tools.alamobile.mod) 不查 PackageManager 可见性，
 * 绕过 Android 11+ 包可见性限制（与 ConfigReceiver 同理）。
 */
class LogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PUSH_GAME_LOG = "tools.alamobile.mod.PUSH_GAME_LOG"
        const val EXTRA_JAVA_LOG = "java_log"
        const val EXTRA_NATIVE_LOG = "native_log"
        private const val GAME_JAVA_LOG_FILE = "game_java.log"
        private const val GAME_NATIVE_LOG_FILE = "game_native.log"
        private const val TAG = "AlaMobileTool"

        /**
         * 游戏进程调用：把日志内容通过非定向广播推到模块进程。
         *
         * Binder transaction buffer 对 Intent extras 有大小限制（通常 ~1MB，
         * 但某些 OEM 更小）。日志可能很大（200KB+），这里截取最近的部分
         *（最有诊断价值），每段最多 MAX_LOG_CHUNK 字节。
         *
         * @return true 广播发送成功
         */
        fun send(context: Context, javaLog: String, nativeLog: String): Boolean {
            return try {
                val trimmedJava = trimLog(javaLog)
                val trimmedNative = trimLog(nativeLog)
                // 用 setComponent 显式投递——不查包可见性、不查 intent-filter，
                // 绕过 Android 11+ 包可见性限制和 AOSP 隐式广播跳过后台静态 receiver 逻辑。
                // FLAG_RECEIVER_INCLUDE_BACKGROUND 强制投递给后台静态 receiver。
                val intent = Intent(ACTION_PUSH_GAME_LOG)
                    .setComponent(android.content.ComponentName("tools.alamobile.mod", "tools.alamobile.mod.config.LogReceiver"))
                    .putExtra(EXTRA_JAVA_LOG, trimmedJava)
                    .putExtra(EXTRA_NATIVE_LOG, trimmedNative)
                    .addFlags(0x0020) // FLAG_RECEIVER_INCLUDE_BACKGROUND — 强制投递给后台静态 receiver
                context.sendBroadcast(intent)
                Log.i(TAG, "LogReceiver.send: explicit broadcast sent (java=${trimmedJava.length} native=${trimmedNative.length})")
                true
            } catch (e: Throwable) {
                Log.w(TAG, "LogReceiver.send failed: ${e.message}")
                false
            }
        }

        /** 截取日志最后 80KB（保留最近的日志，最有诊断价值）。 */
        private fun trimLog(log: String): String {
            val maxLen = 80000
            if (log.length <= maxLen) return log
            val cut = log.length - maxLen
            val newlineIdx = log.indexOf('\n', cut)
            val start = if (newlineIdx >= 0) newlineIdx + 1 else cut
            return "[... 前 ${start} 字符已截断 ...]\n" + log.substring(start)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PUSH_GAME_LOG) return
        val javaLog = intent.getStringExtra(EXTRA_JAVA_LOG) ?: ""
        val nativeLog = intent.getStringExtra(EXTRA_NATIVE_LOG) ?: ""

        Log.i(TAG, "LogReceiver: received game logs (java=${javaLog.length} native=${nativeLog.length})")

        try {
            val cacheDir = context.cacheDir
            if (javaLog.isNotEmpty()) {
                File(cacheDir, GAME_JAVA_LOG_FILE).writeText(javaLog)
            }
            if (nativeLog.isNotEmpty()) {
                File(cacheDir, GAME_NATIVE_LOG_FILE).writeText(nativeLog)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "LogReceiver: write failed: ${e.message}")
        }
    }
}