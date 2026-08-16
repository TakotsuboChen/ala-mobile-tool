package tools.alamobile.mod.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tools.alamobile.mod.EulaManager
import tools.alamobile.mod.LsposedStatus
import tools.alamobile.mod.ui.EulaDialog
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.ui.viewmodel.ConfigUiState
import tools.alamobile.mod.ui.viewmodel.ConfigViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 照搬 KernelSU `SettingPagerMiuix`（SettingsMiuix.kt:69）结构，且每个 preference 项
 * 全部用 miuix preference 组件：
 * - SwitchRow → SwitchPreference
 * - ArrowRow → ArrowPreference
 *
 * 不再有手写 Row+Column+Text+Switch/Clickable——这是 M38 A/B 测试定位的卡顿根因。
 */
@Composable
fun SettingsPagerMiuix(
    uiState: ConfigUiState,
    actions: ConfigViewModel,
    bottomInnerPadding: Dp,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    // 「用户协议」点击后清除同意状态并当场弹协议。
    var showEulaReconfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "设置",
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                    ) {
                        // ── Section 1: 日志 ──
                        SmallTitle(
                            text = "日志",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                title = "启用日志",
                                summary = "记录模块运行日志以便排查问题",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Warning,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.logEnabled,
                                onCheckedChange = actions::setLogEnabled
                            )
                            ArrowPreference(
                                title = "导出并分享日志",
                                summary = "导出当前日志文件并分享",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Share,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    Toast.makeText(context, "日志导出功能即将上线", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        // ── Section 2: 调试 ──
                        SmallTitle(
                            text = "调试",
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = "清除激活标记",
                                summary = "删除 LSPosed / Non-root 激活状态缓存",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    LsposedStatus.clearAll(context)
                                    Toast.makeText(context, "已清除激活标记", Toast.LENGTH_SHORT).show()
                                }
                            )
                            ArrowPreference(
                                title = "用户协议",
                                summary = "重新查看并确认用户协议",
                                startAction = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Article,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    EulaManager.clear(context)
                                    showEulaReconfirm = true
                                }
                            )
                            ArrowPreference(
                                title = "关于",
                                summary = "Ala Mobile Tool 模块信息",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Info,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = onOpenAbout
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEulaReconfirm) {
        EulaDialog(
            sections = EulaManager.EULA_SECTIONS,
            footer = EulaManager.EULA_FOOTER,
            onAccept = {
                EulaManager.accept(context)
                showEulaReconfirm = false
            },
            onExit = {
                showEulaReconfirm = false
                (context as? android.app.Activity)?.finish()
            }
        )
    }
}
