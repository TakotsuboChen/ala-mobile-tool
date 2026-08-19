package tools.alamobile.mod.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.alamobile.mod.EulaManager
import tools.alamobile.mod.LsposedStatus
import tools.alamobile.mod.ui.EulaDialog
import tools.alamobile.mod.update.UpdatePreferences
import tools.alamobile.mod.util.LogExporter
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.ui.viewmodel.ConfigUiState
import tools.alamobile.mod.ui.viewmodel.ConfigViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    // 「用户协议」点击后清除同意状态并当场弹协议。
    var showEulaReconfirm by remember { mutableStateOf(false) }
    // OverlayDialog show 驱动退出动画：关闭时先把 eulaDialogVisible 翻 false 触发动画，
    // onDismissFinished 回调里再执行真正的状态变更。
    var eulaDialogVisible by remember { mutableStateOf(true) }
    var pendingEulaAction by remember { mutableStateOf<() -> Unit>({ }) }

    // 更新通道：0=稳定版，1=预览版
    var updateChannel by remember {
        mutableStateOf(UpdatePreferences.getChannel(context))
    }
    val channelItems = remember {
        listOf(
            DropdownItem(text = "稳定版"),
            DropdownItem(text = "预览版")
        )
    }

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
                        // ── 组 1: 模块更新通道 ──
                        Card(modifier = Modifier.fillMaxWidth()) {
                            OverlaySpinnerPreference(
                                items = channelItems,
                                selectedIndex = updateChannel,
                                title = "模块更新通道",
                                summary = "稳定版仅检查正式 Release，预览版同时检查 Pre-release",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Update,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onSelectedIndexChange = { index ->
                                    updateChannel = index
                                    UpdatePreferences.setChannel(context, index)
                                }
                            )
                        }

                        // ── 组 2: 日志 ──
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
                                    scope.launch {
                                        val uri = withContext(Dispatchers.IO) {
                                            LogExporter.export(context)
                                        }
                                        if (uri != null) {
                                            LogExporter.share(context, uri)
                                        } else {
                                            Toast.makeText(context, "没有可导出的日志", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }

                        // ── 组 3: 激活 / 协议 ──
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
                                title = "清除跳过更新标记",
                                summary = "恢复被跳过版本的自动弹窗提示",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    UpdatePreferences.clearSkippedVersion(context)
                                    Toast.makeText(context, "已清除跳过更新标记", Toast.LENGTH_SHORT).show()
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
                                    eulaDialogVisible = true
                                    showEulaReconfirm = true
                                }
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
            show = eulaDialogVisible,
            onAccept = {
                // 先翻 false 触发退出动画，动画结束后 onDismissFinished 执行真正 accept
                pendingEulaAction = {
                    EulaManager.accept(context)
                    showEulaReconfirm = false
                }
                eulaDialogVisible = false
            },
            onExit = {
                pendingEulaAction = {
                    showEulaReconfirm = false
                    (context as? android.app.Activity)?.finish()
                }
                eulaDialogVisible = false
            },
            onDismissFinished = {
                pendingEulaAction()
            }
        )
    }
}
