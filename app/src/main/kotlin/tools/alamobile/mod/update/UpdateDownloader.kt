package tools.alamobile.mod.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * APK 下载进度回调。
 */
interface DownloadCallback {
    /**
     * 下载进度更新。
     *
     * @param downloadedBytes 已下载字节数
     * @param totalBytes 总字节数（-1 表示未知大小）
     * @param progress 进度百分比 0-100
     */
    fun onProgress(downloadedBytes: Long, totalBytes: Long, progress: Int)

    /** 下载成功，返回 APK 文件。 */
    fun onSuccess(file: File)

    /** 下载失败。 */
    fun onError(message: String)
}

/**
 * 下载更新 APK。
 *
 * 同时尝试官方下载 URL 和镜像站 URL（把 `github.com` 替换为 `kkgithub.com`），
 * 哪个先连上用哪个。下载到 `cacheDir/download/` 目录。
 *
 * 下载过程在 IO 线程，通过 [DownloadCallback] 回调主线程进度。
 */
object UpdateDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 下载 APK。
     *
     * @param context 用于获取下载目录
     * @param downloadUrl GitHub Release asset 的 browser_download_url
     * @param fileName 保存的文件名
     * @param callback 进度回调
     */
    suspend fun download(
        context: Context,
        downloadUrl: String,
        fileName: String,
        callback: DownloadCallback
    ) = withContext(Dispatchers.IO) {
        val dir = UpdatePreferences.getDownloadDirectory(context)
        val outputFile = File(dir, fileName)

        // 镜像 URL：把 github.com 替换为 kkgithub.com
        val mirrorUrl = downloadUrl.replace("github.com", "kkgithub.com")

        val urls = listOf(downloadUrl, mirrorUrl)

        var lastError: String? = null
        for (url in urls) {
            try {
                downloadFromUrl(url, outputFile, callback)
                return@withContext // 成功，直接返回
            } catch (e: Exception) {
                lastError = e.message ?: "下载失败"
                // 清理半成品文件
                if (outputFile.exists()) outputFile.delete()
                // 尝试下一个 URL
            }
        }

        withContext(Dispatchers.Main) {
            callback.onError(lastError ?: "下载失败")
        }
    }

    private suspend fun downloadFromUrl(
        url: String,
        outputFile: File,
        callback: DownloadCallback
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }
            val body = response.body ?: throw Exception("响应体为空")
            val totalBytes = body.contentLength()

            outputFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L
                    var lastReportedProgress = -1

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val progress = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt()
                        } else {
                            -1
                        }

                        // 只在进度变化时回调，避免主线程过载
                        if (progress != lastReportedProgress) {
                            lastReportedProgress = progress
                            withContext(Dispatchers.Main) {
                                callback.onProgress(downloadedBytes, totalBytes, progress)
                            }
                        }
                    }
                }
            }

            // 下载完成
            withContext(Dispatchers.Main) {
                callback.onSuccess(outputFile)
            }
        }
    }
}