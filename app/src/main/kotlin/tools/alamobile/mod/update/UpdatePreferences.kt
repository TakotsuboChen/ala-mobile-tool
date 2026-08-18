package tools.alamobile.mod.update

import android.content.Context
import java.io.File

/**
 * 更新相关的持久化状态。
 *
 * 存储在 SharedPreferences `update_prefs` 里：
 * - `skipped_version_code`：用户点「跳过该版本」时记录的 versionCode，下次检查到该版本不再弹窗。
 * - `downloaded_version_code`：上次下载的 APK 对应的 versionCode，用于下次启动时判断是否需要清理旧 APK。
 *
 * APK 下载目录：`context.cacheDir/download/`，文件名取 GitHub Release asset name。
 */
object UpdatePreferences {

    private const val PREFS_NAME = "update_prefs"
    private const val KEY_SKIPPED = "skipped_version_code"
    private const val KEY_DOWNLOADED = "downloaded_version_code"
    private const val KEY_DOWNLOADED_FILE = "downloaded_file_name"
    private const val KEY_CHANNEL = "update_channel"

    /**
     * 更新通道：0 = 稳定版（仅 Release），1 = 预览版（Release + Pre-release）。
     */
    const val CHANNEL_STABLE = 0
    const val CHANNEL_PREVIEW = 1

    /** 下载目录：cacheDir/download，被 FileProvider 的 cache-path "download" 覆盖。 */
    private fun downloadDir(context: Context): File {
        return File(context.cacheDir, "download").also { if (!it.exists()) it.mkdirs() }
    }

    /**
     * 获取更新通道（0=稳定版，1=预览版），默认稳定版。
     */
    fun getChannel(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CHANNEL, CHANNEL_STABLE)
    }

    /**
     * 设置更新通道。
     */
    fun setChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CHANNEL, channel)
            .apply()
    }

    /**
     * 用户跳过的 versionCode，null 表示没有跳过任何版本。
     */
    fun getSkippedVersionCode(context: Context): Int? {
        val v = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SKIPPED, -1)
        return if (v == -1) null else v
    }

    /**
     * 标记跳过指定版本。
     */
    fun skipVersion(context: Context, versionCode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SKIPPED, versionCode)
            .apply()
    }

    /**
     * 清除跳过的版本标记，下次自动检查将重新弹窗。
     */
    fun clearSkippedVersion(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SKIPPED)
            .apply()
    }

    /**
     * 记录已下载的 APK 信息（用于下次启动清理）。
     */
    fun setDownloadedApk(context: Context, versionCode: Int, fileName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DOWNLOADED, versionCode)
            .putString(KEY_DOWNLOADED_FILE, fileName)
            .apply()
    }

    /**
     * 启动时检查是否需要清理旧 APK：
     * 如果有已下载的 APK 且当前版本 > 已下载版本，说明用户已安装新版本，
     * 删除旧 APK 文件并清空记录。
     *
     * @param currentVersionCode 当前应用的 versionCode
     */
    fun cleanupOldApkIfNeeded(context: Context, currentVersionCode: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val downloadedCode = prefs.getInt(KEY_DOWNLOADED, -1)
        val downloadedFile = prefs.getString(KEY_DOWNLOADED_FILE, null)

        if (downloadedCode != -1 && downloadedFile != null) {
            if (currentVersionCode >= downloadedCode) {
                // 已安装新版本（或相同版本），清理旧 APK
                val file = File(downloadDir(context), downloadedFile)
                if (file.exists()) file.delete()
                // 同时清理目录里其他残留 APK（之前版本遗留的）
                downloadDir(context).listFiles()?.forEach {
                    if (it.name.endsWith(".apk")) it.delete()
                }
                prefs.edit()
                    .remove(KEY_DOWNLOADED)
                    .remove(KEY_DOWNLOADED_FILE)
                    .apply()
            }
        }
    }

    /**
     * 获取下载目录，供 Downloader 使用。
     */
    fun getDownloadDirectory(context: Context): File = downloadDir(context)

    /**
     * 获取已下载的 APK 文件（如果存在）。
     */
    fun getDownloadedApk(context: Context): File? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val downloadedFile = prefs.getString(KEY_DOWNLOADED_FILE, null) ?: return null
        val file = File(downloadDir(context), downloadedFile)
        return if (file.exists()) file else null
    }

    /**
     * 检查指定版本是否已下载过 APK。
     *
     * 判断条件：prefs 里记录的 versionCode 匹配，且文件实际存在。
     * 用于避免同一版本重复下载。
     */
    fun hasDownloadedApk(context: Context, versionCode: Int): File? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val downloadedCode = prefs.getInt(KEY_DOWNLOADED, -1)
        if (downloadedCode != versionCode) return null
        return getDownloadedApk(context)
    }
}