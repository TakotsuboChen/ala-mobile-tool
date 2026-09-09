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
import androidx.compose.ui.graphics.Color
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
 * 更新弹窗（强制升级）。
 *
 * 两种状态：
 * - **信息态**：展示 Release Note + 「退出模块」「下载更新/安装更新」按钮。
 * - **下载态**：显示下载百分比文本，无按钮（不可取消/不可后台，下载完成自动调起安装器）。
 *
 * 弹窗不可通过点外部或返回键关闭（不传 [OverlayDialog] 的 onDismissRequest）——
 * 唯一出路是「退出模块」（退出动画播完后 finish Activity）或完成更新自动安装。
 * 下载失败回信息态显示错误文本，可再次点击下载重试。
 *
 * 退出动画与 [EulaDialog] 同模式（两变量契约）：pendingAction 在 onDismissFinished 里执行，
 * 保证 finish 发生在退出动画播完之后。
 *
 * @param show 控制弹窗显示/隐藏
 * @param updateInfo 新版本信息
 * @param onRequestClose 请求关闭（翻外部 show=false 触发退出动画）
 * @param onDismissFinished 退出动画完成回调
 */
@Composable
fun UpdateDialog(
    show: Boolean,
    updateInfo: UpdateInfo,
    onRequestClose: () -> Unit,
    onDismissFinished: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var downloadStatus by remember { mutableStateOf("") }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<() -> Unit>({ }) }

    OverlayDialog(
        show = show,
        title = "发现新版本",
        onDismissFinished = {
            onDismissFinished()
            pendingAction()
        },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isDownloading) {
                    // 下载态：进度文本，无按钮（强制升级：不可取消/不可后台）
                    Text(
                        text = downloadStatus,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
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
                            text = "退出模块",
                            onClick = {
                                // 先翻 show=false 播退出动画，动画结束后 onDismissFinished 才 finish
                                pendingAction = { (context as? android.app.Activity)?.finish() }
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
                                    return@TextButton
                                }

                                isDownloading = true
                                downloadError = null
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
                                                isDownloading = false
                                                downloadStatus = ""
                                            }

                                            override fun onError(message: String) {
                                                // 回信息态显示错误，可再次点击下载重试
                                                downloadError = message
                                                isDownloading = false
                                                downloadStatus = ""
                                            }
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                    // 下载失败错误提示（信息态下显示）
                    downloadError?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "下载失败：$message",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = Color.Red
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
