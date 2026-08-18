package tools.alamobile.mod.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import tools.alamobile.mod.BuildConfig
import tools.alamobile.mod.update.DownloadCallback
import tools.alamobile.mod.update.UpdateDownloader
import tools.alamobile.mod.update.UpdateInfo
import tools.alamobile.mod.update.UpdatePreferences
import java.io.File

/**
 * 更新弹窗。
 *
 * 两种状态：
 * - **信息态**：展示新版本号 + Release Note + 「跳过该版本」「下载更新」按钮。
 * - **下载态**：显示下载百分比文本，「跳过该版本」按钮变为「取消下载」。
 *
 * 退出动画与 [EulaDialog] 同模式。
 *
 * @param show 控制弹窗显示/隐藏
 * @param updateInfo 新版本信息
 * @param onRequestClose 请求关闭（翻外部 show=false 触发退出动画）
 * @param onDismissFinished 退出动画完成回调
 * @param onSkipped 用户点「跳过该版本」，调用方记录跳过的 versionCode
 */
@Composable
fun UpdateDialog(
    show: Boolean,
    updateInfo: UpdateInfo,
    onRequestClose: () -> Unit,
    onDismissFinished: () -> Unit,
    onSkipped: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadStatus by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<() -> Unit>({ }) }

    OverlayDialog(
        show = show,
        title = "发现新版本",
        onDismissRequest = {
            if (!isDownloading) {
                pendingAction = { }
                onRequestClose()
            }
        },
        onDismissFinished = {
            onDismissFinished()
            pendingAction()
        },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isDownloading) {
                    // 下载态：进度文本
                    Text(
                        text = downloadStatus,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            text = "取消下载",
                            onClick = {
                                // 简化：直接回到信息态，已下载部分会留在 cache
                                pendingAction = { }
                                isDownloading = false
                                downloadProgress = 0
                                downloadStatus = ""
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                        TextButton(
                            text = "后台下载",
                            onClick = {
                                pendingAction = { }
                                onRequestClose()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                } else {
                    // 信息态：Release Note + 按钮
                    // 检查是否已下载该版本 APK，有则按钮显示"安装更新"而非"下载更新"
                    val existingApk = updateInfo.latestVersionCode?.let {
                        UpdatePreferences.hasDownloadedApk(context, it)
                    }
                    MarkdownText(
                        markdown = updateInfo.releaseNote.ifBlank { "暂无更新说明" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            text = "跳过该版本",
                            onClick = {
                                pendingAction = { onSkipped() }
                                onRequestClose()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                        TextButton(
                            text = if (existingApk != null) "安装更新" else "下载更新",
                            onClick = {
                                // 如果已有该版本 APK，直接调起安装器，不重复下载
                                val targetVersionCode = updateInfo.latestVersionCode
                                    ?: BuildConfig.VERSION_CODE
                                val existing = targetVersionCode.let {
                                    UpdatePreferences.hasDownloadedApk(context, it)
                                }
                                if (existing != null) {
                                    installApk(context, existing)
                                    pendingAction = { }
                                    onRequestClose()
                                    return@TextButton
                                }

                                isDownloading = true
                                downloadStatus = "正在下载..."
                                scope.launch {
                                    UpdateDownloader.download(
                                        context = context,
                                        downloadUrl = updateInfo.apkDownloadUrl,
                                        fileName = updateInfo.apkFileName,
                                        callback = object : DownloadCallback {
                                            override fun onProgress(
                                                downloadedBytes: Long,
                                                totalBytes: Long,
                                                progress: Int
                                            ) {
                                                downloadProgress = progress
                                                downloadStatus = if (totalBytes > 0) {
                                                    "正在下载... $progress%"
                                                } else {
                                                    "正在下载... ${downloadedBytes / 1024}KB"
                                                }
                                            }

                                            override fun onSuccess(file: File) {
                                                UpdatePreferences.setDownloadedApk(
                                                    context,
                                                    updateInfo.latestVersionCode
                                                        ?: BuildConfig.VERSION_CODE,
                                                    file.name
                                                )
                                                installApk(context, file)
                                                pendingAction = { }
                                                isDownloading = false
                                                onRequestClose()
                                            }

                                            override fun onError(message: String) {
                                                downloadStatus = "下载失败：$message"
                                                isDownloading = false
                                                downloadProgress = 0
                                            }
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    )
}

/**
 * 通过 FileProvider 调起系统安装器安装 APK。
 */
private fun installApk(context: Context, apkFile: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}