package tools.alamobile.mod.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log

/**
 * 「所有文件访问」（MANAGE_EXTERNAL_STORAGE / All Files Access）权限助手。
 *
 * **为什么模块必须有这个权限**（2026-09-12 定案）：
 * 模块要把最新配置/登录态传给游戏，唯一在"模块 App 不运行 + ColorOS 关关联启动 +
 * 游戏 stopped state"三重约束下仍可用的通道，是写 `/sdcard/Android/media/<游戏包>/`
 * 文件（游戏进程读**自己包**的 media 目录，同 uid 零权限）。而模块 App 要写**他包**
 * 的 media 目录，必须持 AFA —— `Android/data` 被官方明确排除，`Android/media` 不在
 * 受限区，AFA 后可读写（实机验证）。
 *
 * 未授权时模块仍能启动（旧通道尽力而为），但配置/登录态无法可靠送达 → 用满屏
 * 不可跳过的 [tools.alamobile.mod.ui.PermissionGateScreen] 强制引导。
 */
object AllFilesPermission {

    private const val TAG = "AlaMobileTool"

    /** API 30+ 才有 MANAGE_EXTERNAL_STORAGE 概念；<30 时旧存储权限足够，视为已授权。 */
    fun isGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return try {
            Environment.isExternalStorageManager()
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "isExternalStorageManager failed: ${e.message}")
            false
        }
    }

    /**
     * 跳到**本应用**的「所有文件访问」设置页（一键开关）。API 30+ 用带包名的
     * ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION；部分 OEM（含 ColorOS）不认这个
     * 带包名的 action，回落到全局页 ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION。
     */
    fun openSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(appIntent)
            return
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "app-scoped AFA settings unavailable: ${e.message}")
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            Logger.log(Log.ERROR, TAG, "AFA settings unavailable: ${e.message}")
        }
    }
}
