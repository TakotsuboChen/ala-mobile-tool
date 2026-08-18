package tools.alamobile.mod.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import tools.alamobile.mod.R

/**
 * 支持开发捐赠弹窗。
 *
 * 展示收款码图片和文案，提供两个按钮：
 * - 左边灰色「继续免费使用」——关闭弹窗。
 * - 右边蓝色「保存收款码」——将收款码 PNG 保存到 Pictures/Ala Mobile Tool 目录，
 *   Toast 提示"已保存到相册，感谢您的支持！"，然后关闭弹窗。
 *
 * 退出动画与 [EulaDialog] 同模式：[show] 从 true→false 触发退出动画，
 * [onDismissFinished] 回调里调用方真正清理状态。按钮点击时调用 [onRequestClose]
 * 翻外部 show=false 触发退出动画，真正的副作用在 [onDismissFinished] 里执行。
 *
 * @param show 控制弹窗显示/隐藏。
 * @param onRequestClose 请求关闭弹窗（翻外部 show=false 触发退出动画）。
 * @param onDismissFinished 退出动画播完的回调，调用方在此清理状态。
 */
@Composable
fun SupportDialog(
    show: Boolean,
    onRequestClose: () -> Unit,
    onDismissFinished: () -> Unit
) {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<() -> Unit>({ }) }

    OverlayDialog(
        show = show,
        title = "支持开发",
        onDismissRequest = {
            pendingAction = { }
            onRequestClose()
        },
        onDismissFinished = {
            onDismissFinished()
            pendingAction()
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "您的支持是我持续开发的最大动力",
                    fontSize = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body1.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Image(
                    painter = painterResource(R.drawable.pay_qrcode),
                    contentDescription = "收款码",
                    modifier = Modifier.size(240.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        text = "继续免费使用",
                        onClick = {
                            pendingAction = { }
                            onRequestClose()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        text = "保存收款码",
                        onClick = {
                            pendingAction = {
                                saveQrCodeToGallery(context)
                            }
                            onRequestClose()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

/**
 * 将 drawable 里的收款码 PNG 保存到 Pictures/Ala Mobile Tool 目录。
 *
 * Android 10+ 用 MediaStore 写入 MediaStore.Images，兼容 scoped storage；
 * Android 9 及以下直接写外部存储公共 Pictures 目录。
 */
private fun saveQrCodeToGallery(context: Context) {
    val dirName = "Ala Mobile Tool"
    val fileName = "PayQrcode.png"

    val bitmap = android.graphics.BitmapFactory.decodeResource(
        context.resources, R.drawable.pay_qrcode
    )
    if (bitmap == null) {
        Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
        return
    }

    val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android 10+：通过 MediaStore 写入 Pictures 目录
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$dirName")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            false
        }
    } else {
        // Android 9 及以下：直接写文件
        val dir = java.io.File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            dirName
        )
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, fileName)
        java.io.FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        // 通知媒体扫描
        android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).also {
            it.data = android.net.Uri.fromFile(file)
            context.sendBroadcast(it)
        }
        true
    }

    if (saved) {
        Toast.makeText(context, "已保存到相册，感谢您的支持！", Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
    }
}