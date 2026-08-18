package tools.alamobile.mod.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.io.File

/**
 * 跨进程配置 IPC（Android 11+ scoped storage 下文件共享失效后的替代方案）。
 *
 * - 模块进程（ConfigActivity）调 ModConfig.write 直接写 filesDir（天然可达）。
 * - 游戏进程（OverlayManager）调 ConfigProvider.call(READ) 读配置 JSON 字符串，
 *   再由 ModConfig.fromJson 解析。调用经 Binder 路由到模块进程执行，模块进程用
 *   自己的 filesDir 读写，scoped storage 不再拦截。
 *
 * Provider 在游戏进程 call 时会被系统自动拉起模块进程，~100ms 延迟对配置读取
 * （低频、启动时一次）可接受。
 */
class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "tools.alamobile.mod.config"
        const val READ_METHOD = "read_config"
        const val PUSH_GAME_LOG_METHOD = "push_game_log"
        const val READ_GAME_LOG_METHOD = "read_game_log"
        const val KEY_JSON = "json"
        const val KEY_JAVA_LOG = "java_log"
        const val KEY_NATIVE_LOG = "native_log"

        private const val FILE_NAME = "ala_tool_config.json"
        private const val GAME_JAVA_LOG_FILE = "game_java.log"
        private const val GAME_NATIVE_LOG_FILE = "game_native.log"

        fun uri(): Uri = Uri.parse("content://$AUTHORITY")

        /**
         * 游戏进程调用：把 Java/native 日志内容推到模块进程缓存。
         * 模块进程的 LogExporter 之后从这里读。
         */
        fun pushGameLog(context: Context, javaLog: String, nativeLog: String): Boolean {
            return try {
                val uri = Uri.parse("content://$AUTHORITY")
                val extras = Bundle().apply {
                    putString(KEY_JAVA_LOG, javaLog)
                    putString(KEY_NATIVE_LOG, nativeLog)
                }
                context.contentResolver.call(uri, PUSH_GAME_LOG_METHOD, null, extras)
                true
            } catch (e: Throwable) {
                android.util.Log.w("AlaMobileTool", "ConfigProvider.pushGameLog failed: ${e.message}")
                false
            }
        }

        /**
         * 模块进程调用：读取游戏进程推过来的日志缓存。
         * @return Pair(javaLog, nativeLog>，如果没推过返回 null
         */
        fun readGameLog(context: Context): Pair<String, String>? {
            return try {
                val uri = Uri.parse("content://$AUTHORITY")
                val result = context.contentResolver.call(uri, READ_GAME_LOG_METHOD, null, null)
                val javaLog = result?.getString(KEY_JAVA_LOG)
                val nativeLog = result?.getString(KEY_NATIVE_LOG)
                if (javaLog != null || nativeLog != null) {
                    (javaLog ?: "") to (nativeLog ?: "")
                } else null
            } catch (_: Throwable) {
                null
            }
        }
    }

    private lateinit var providerContext: Context

    override fun onCreate(): Boolean {
        providerContext = context ?: return false
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            READ_METHOD -> {
                // 读模块进程 filesDir 下的配置文件原始 JSON 字符串，交给游戏进程用
                // ModConfig.fromJson 解析。文件不存在时返回空串，调用方回退默认值。
                val file = File(providerContext.filesDir, FILE_NAME)
                val json = if (file.exists()) {
                    try { file.readText() } catch (_: Throwable) { "" }
                } else ""
                return Bundle().apply {
                    putString(KEY_JSON, json)
                }
            }
            PUSH_GAME_LOG_METHOD -> {
                // 游戏进程推送日志内容到模块进程缓存
                val javaLog = extras?.getString(KEY_JAVA_LOG) ?: ""
                val nativeLog = extras?.getString(KEY_NATIVE_LOG) ?: ""
                val cacheDir = providerContext.cacheDir
                android.util.Log.i("AlaMobileTool", "ConfigProvider.pushGameLog: java=${javaLog.length} native=${nativeLog.length} cacheDir=${cacheDir.absolutePath}")
                try {
                    if (javaLog.isNotEmpty()) {
                        File(cacheDir, GAME_JAVA_LOG_FILE).writeText(javaLog)
                        android.util.Log.i("AlaMobileTool", "ConfigProvider.pushGameLog: wrote game_java.log (${javaLog.length} bytes)")
                    }
                    if (nativeLog.isNotEmpty()) {
                        File(cacheDir, GAME_NATIVE_LOG_FILE).writeText(nativeLog)
                        android.util.Log.i("AlaMobileTool", "ConfigProvider.pushGameLog: wrote game_native.log (${nativeLog.length} bytes)")
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("AlaMobileTool", "ConfigProvider.pushGameLog: write failed: ${e.message}")
                }
                return Bundle()
            }
            READ_GAME_LOG_METHOD -> {
                // 模块进程读游戏进程推过来的日志缓存
                val cacheDir = providerContext.cacheDir
                android.util.Log.i("AlaMobileTool", "ConfigProvider.readGameLog: cacheDir=${cacheDir.absolutePath}")
                val javaLog = try {
                    val f = File(cacheDir, GAME_JAVA_LOG_FILE)
                    val exists = f.exists()
                    val len = if (exists) f.length() else 0
                    android.util.Log.i("AlaMobileTool", "ConfigProvider.readGameLog: game_java.log exists=$exists len=$len")
                    if (exists) f.readText() else null
                } catch (e: Throwable) {
                    android.util.Log.w("AlaMobileTool", "ConfigProvider.readGameLog: java read failed: ${e.message}")
                    null
                }
                val nativeLog = try {
                    val f = File(cacheDir, GAME_NATIVE_LOG_FILE)
                    val exists = f.exists()
                    val len = if (exists) f.length() else 0
                    android.util.Log.i("AlaMobileTool", "ConfigProvider.readGameLog: game_native.log exists=$exists len=$len")
                    if (exists) f.readText() else null
                } catch (e: Throwable) {
                    android.util.Log.w("AlaMobileTool", "ConfigProvider.readGameLog: native read failed: ${e.message}")
                    null
                }
                return Bundle().apply {
                    if (javaLog != null) putString(KEY_JAVA_LOG, javaLog)
                    if (nativeLog != null) putString(KEY_NATIVE_LOG, nativeLog)
                }
            }
            else -> return null
        }
    }
}
