package tools.alamobile.mod.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 轻量 Markdown 渲染组件。
 *
 * 支持 GitHub Release Note 常见的语法：
 * - 标题 `#` `##` `###`
 * - 粗体 `**text**`
 * - 行内代码 `` `code` ``
 * - 无序列表 `- ` 或 `* `
 * - 有序列表 `1. `
 * - 链接 `[text](url)`（显示为蓝色带下划线）
 * - 代码块 ``` ``` ```（等宽字体显示）
 * - 分隔线 `---`
 *
 * 不支持的语法以纯文本显示。不引入第三方 Markdown 库以避免增加 APK 体积。
 *
 * @param maxHeightFraction 内容最大高度占设备当前方向屏幕高度的比例。
 *   内容不超过此高度时全部展开，超过时变为可滚动。默认 0.6（60%）。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.6f
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val scrollState = rememberScrollState()
    val screenHeight = LocalWindowInfo.current.containerDpSize.height
    val maxContentHeight = screenHeight * maxHeightFraction

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxContentHeight)
            .verticalScroll(scrollState)
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    Text(
                        text = block.text,
                        fontSize = block.size.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = block.text,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                is MarkdownBlock.ListItem -> {
                    val prefix = if (block.ordered) "${block.index}. " else "• "
                    Text(
                        text = AnnotatedString(prefix) + block.text,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    Text(
                        text = block.text,
                        fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                is MarkdownBlock.Divider -> {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (index < blocks.lastIndex) {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

// ── Markdown 解析 ──

private sealed class MarkdownBlock {
    data class Heading(val text: AnnotatedString, val size: Int) : MarkdownBlock()
    data class Paragraph(val text: AnnotatedString) : MarkdownBlock()
    data class ListItem(val text: AnnotatedString, val ordered: Boolean, val index: Int) : MarkdownBlock()
    data class CodeBlock(val text: AnnotatedString) : MarkdownBlock()
    object Divider : MarkdownBlock()
}

/**
 * 解析 Markdown 为块列表。
 * 先按行分割，识别代码块围栏，再逐行处理。
 */
private fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // 代码块围栏 ```
        if (line.trim().startsWith("```")) {
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            i++ // 跳过结束 ```
            blocks.add(MarkdownBlock.CodeBlock(
                text = AnnotatedString(codeLines.joinToString("\n"))
            ))
            continue
        }

        // 分隔线 ---
        if (line.trim().matches(Regex("^-{3,}$"))) {
            blocks.add(MarkdownBlock.Divider)
            i++
            continue
        }

        // 标题 # ## ###
        val headingMatch = Regex("^(#{1,3})\\s+(.+)").find(line)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val content = headingMatch.groupValues[2]
            val size = when (level) {
                1 -> 20
                2 -> 17
                else -> 15
            }
            blocks.add(MarkdownBlock.Heading(
                text = parseInline(content),
                size = size
            ))
            i++
            continue
        }

        // 无序列表 - 或 *
        val unorderedMatch = Regex("^[\\-*]\\s+(.+)").find(line)
        if (unorderedMatch != null) {
            val content = unorderedMatch.groupValues[1]
            blocks.add(MarkdownBlock.ListItem(
                text = parseInline(content),
                ordered = false,
                index = 0
            ))
            i++
            continue
        }

        // 有序列表 1. 2.
        val orderedMatch = Regex("^(\\d+)\\.\\s+(.+)").find(line)
        if (orderedMatch != null) {
            val num = orderedMatch.groupValues[1].toIntOrNull() ?: 1
            val content = orderedMatch.groupValues[2]
            blocks.add(MarkdownBlock.ListItem(
                text = parseInline(content),
                ordered = true,
                index = num
            ))
            i++
            continue
        }

        // 空行跳过
        if (line.isBlank()) {
            i++
            continue
        }

        // 普通段落：合并连续非空行
        val paraLines = mutableListOf(line)
        i++
        while (i < lines.size && lines[i].isNotBlank() &&
            !lines[i].trim().startsWith("```") &&
            !lines[i].trim().matches(Regex("^-{3,}$")) &&
            !lines[i].trim().startsWith("#") &&
            !lines[i].matches(Regex("^[\\-*]\\s+.+")) &&
            !lines[i].matches(Regex("^\\d+\\.\\s+.+"))
        ) {
            paraLines.add(lines[i])
            i++
        }
        blocks.add(MarkdownBlock.Paragraph(
            text = parseInline(paraLines.joinToString(" "))
        ))
    }

    return blocks
}

/**
 * 解析行内格式：粗体 **text**、行内代码 `code`、链接 [text](url)。
 */
private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        // 粗体 **text**
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end != -1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        // 行内代码 `code`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end != -1) {
                withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // 链接 [text](url)
        if (text[i] == '[') {
            val textEnd = text.indexOf(']', i + 1)
            if (textEnd != -1 && textEnd + 1 < text.length && text[textEnd + 1] == '(') {
                val urlEnd = text.indexOf(')', textEnd + 2)
                if (urlEnd != -1) {
                    val linkText = text.substring(i + 1, textEnd)
                    withStyle(SpanStyle(
                        color = androidx.compose.ui.graphics.Color(0xFF0066CC),
                        textDecoration = TextDecoration.Underline
                    )) {
                        append(linkText)
                    }
                    i = urlEnd + 1
                    continue
                }
            }
        }
        append(text[i])
        i++
    }
}