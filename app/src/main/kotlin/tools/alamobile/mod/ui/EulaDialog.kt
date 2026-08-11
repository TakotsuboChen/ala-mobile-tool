package tools.alamobile.mod.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import tools.alamobile.mod.EulaManager.EulaSection

/**
 * 用户条款确认弹窗。
 *
 * 弹出时机与激活状态弹窗（NonRootConfirmDialog）一致：在 [tools.alamobile.mod.ui.ConfigMainScreen]
 * 渲染之后、作为 OverlayDialog 弹出，且优先于激活状态弹窗显示（[configAccepted] 控制顺序）。
 *
 * 点「不同意」或按返回键触发 [onExit]（finish 退出 Activity）；点「同意」后才进入正常使用。
 */
@Composable
fun EulaDialog(
    sections: List<EulaSection>,
    footer: String,
    onAccept: () -> Unit,
    onExit: () -> Unit
) {
    OverlayDialog(
        show = true,
        title = "用户协议",
        onDismissRequest = onExit,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 可滚动条款正文
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp)
                ) {
                    // 章节：粗体小标题 + 正文
                    sections.forEachIndexed { index, section ->
                        Text(
                            text = section.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = section.body,
                            fontSize = 14.sp
                        )
                        if (index != sections.lastIndex) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = footer,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        text = "不同意",
                        onClick = onExit,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        text = "同意",
                        onClick = onAccept,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}