package tools.alamobile.mod.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 围场指南弹窗：居中粗体标题「围场指南」+ 左对齐 Markdown 正文 + 唯一蓝色全宽
 * 「我已了解」。
 *
 * **阅读门控**：正文需滚到底（或视口容得下）按钮才可点击，与 [EulaDialog] 的
 * 「请先阅读协议」同模式。
 *
 * **锁死**：`onDismissRequest` 传空实现——点窗口外面/按返回键都无操作，用户必须
 * 点「我已了解」才能关闭（用户明确要求：不能点外面就退出 App，也不能点外面关掉）。
 *
 * 退出动画沿用项目弹窗铁律：`show` 由调用方驱动，true→false 触发退出动画。
 *
 * @param show 控制显示/隐藏；调用方翻 false 触发退出动画。
 * @param markdown 指南正文（Markdown）。
 * @param onAccept 点「我已了解」——调用方在此落「已读」标记并翻 show=false。
 * @param onDismissFinished 退出动画播完回调（调用方做卸载清理）。
 */
@Composable
fun PaddockGuideDialog(
    show: Boolean,
    markdown: String,
    onAccept: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    // 滚动状态提到外面，才能用 maxValue 做"读完才可点"的门控。
    val scrollState = rememberScrollState()
    val hasRead by remember(scrollState) {
        derivedStateOf {
            // maxValue==0 = 视口容得下（无需滚动）也视为已读。
            scrollState.maxValue == 0 || scrollState.value >= scrollState.maxValue
        }
    }
    OverlayDialog(
        show = show,
        // 锁死：点外面/返回键不发任何事（不关闭、不退出 App）。
        onDismissRequest = { },
        onDismissFinished = onDismissFinished,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "围场指南",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                MarkdownText(
                    markdown = markdown,
                    maxHeightFraction = 0.55f,
                    textSizeSp = 14f,
                    scrollState = scrollState,
                )
                TextButton(
                    text = if (hasRead) "我已了解" else "请先阅读指南",
                    onClick = onAccept,
                    enabled = hasRead,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
