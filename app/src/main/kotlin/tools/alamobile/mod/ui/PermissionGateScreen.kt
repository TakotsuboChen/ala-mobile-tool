package tools.alamobile.mod.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import tools.alamobile.mod.util.AllFilesPermission

/**
 * 「必要权限」满屏不可跳过门（2026-09-12）。
 *
 * 未授予「所有文件访问」（AFA）时占据整个主界面，用户无法进入模块任何页面。
 * 这是硬性前置：无 AFA 时模块**无法**把配置/登录态可靠传给游戏（跨包 media 文件
 * 通道是唯一在三重约束下可用的通道，见 [tools.alamobile.mod.config.CrossPkgMailbox]）。
 *
 * 交互（用户定案文案）：
 * - 大标题「必要权限」
 * - 卡片：文件夹 Icon + 标题「授予所有文件访问权限」+ 描述「若无法获得该权限模块将无法正常运行」
 *   + 右侧红字「未授权」
 * - 底部左灰「退出模块」（finish）/ 右蓝「去授予权限」（跳设置页）
 * - 用户开完开关返回模块 → [onResumeCheck] 触发重查 → 已授权则自动进主界面
 *
 * @param onResumeCheck 返回前台时调用：返回已授权则门消失
 */
@Composable
fun PermissionGateScreen() {
    val context = LocalContext.current
    Scaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "必要权限",
                fontSize = MiuixTheme.textStyles.title1.fontSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "授予所有文件访问权限",
                            fontSize = MiuixTheme.textStyles.body1.fontSize,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "若无法获得该权限模块将无法正常运行",
                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "未授权",
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                        color = Color(0xFFE53935),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = "退出模块",
                    onClick = { (context as? Activity)?.finish() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = "去授予权限",
                    onClick = { AllFilesPermission.openSettings(context) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
