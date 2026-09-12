package tools.alamobile.mod.config

import tools.alamobile.mod.util.Logger
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
        const val READ_TOKEN_METHOD = "read_token"
        const val KEY_JSON = "json"
        const val KEY_TOKEN = "paddock_token"

        private const val FILE_NAME = "ala_tool_config.json"
        private const val AUTH_FILE_NAME = "paddock_auth.json"

        fun uri(): Uri = Uri.parse("content://$AUTHORITY")
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
            READ_TOKEN_METHOD -> {
                // 读 paddock auth 文件里的 token。NPatch 本地模式下 Remote Preferences
                // 是空壳（loader 的 requestRemotePreferences 返回 Bundle.EMPTY，模块 App
                // 经管理器写入的 daemon db 与游戏进程本地 fallback store 是两个物理
                // 文件），token 唯一可达通道 = ConfigProvider（游戏进程 call 时系统
                // 自动拉起模块进程，exported=true 已验证可用）。
                // ⚠️ 目录必须与 PaddockClient.saveAuth 的 getDir() 一致：
                // getExternalFilesDir(null)（/sdcard/Android/data/<pkg>/files/），
                // 不能用 filesDir——两处不同，读 filesDir 永远 null（用户日志实证）。
                val token = try {
                    val extDir = providerContext.getExternalFilesDir(null)
                    val f = if (extDir != null) File(extDir, AUTH_FILE_NAME)
                            else File(providerContext.filesDir, AUTH_FILE_NAME)
                    if (f.exists()) {
                        org.json.JSONObject(f.readText()).optString("token", "").takeIf { it.isNotEmpty() }
                    } else null
                } catch (_: Throwable) { null }
                Logger.i("AlaMobileTool", "ConfigProvider.readToken: ${if (token != null) "len=${token.length}" else "null"}")
                return Bundle().apply { putString(KEY_TOKEN, token) }
            }
            else -> return null
        }
    }
}
