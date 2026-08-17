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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * 弹出时机：在概览页（[tools.alamobile.mod.ui.screen.overview.OverviewPagerMiuix]）的
 * Scaffold popupHost 里渲染，优先级高于激活状态弹窗（NonRootConfirmDialog）。
 * 未同意时激活弹窗不触发（eulaAccepted 门控），点「同意」后放行。
 *
 * 「同意」按钮需滚到底部阅读完才可点击；点「不同意」或按返回键触发 [onExit]（finish 退出 Activity）。
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
                val scrollState = rememberScrollState()
                // maxValue==0 时视口容得下（无需滚动）也视为已读完。
                val hasScrolledToBottom by remember {
                    derivedStateOf {
                        scrollState.maxValue == 0 || scrollState.value >= scrollState.maxValue
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(scrollState)
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
                        text = if (hasScrolledToBottom) "同意" else "请先阅读协议",
                        onClick = onAccept,
                        modifier = Modifier.weight(1f),
                        enabled = hasScrolledToBottom,
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}