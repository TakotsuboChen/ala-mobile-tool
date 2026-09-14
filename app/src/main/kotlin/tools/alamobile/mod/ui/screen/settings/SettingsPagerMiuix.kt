package tools.alamobile.mod.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Update
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
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
    // EulaDialog 的 show 驱动退出动画：关闭时先把 eulaDialogVisible 翻 false 触发动画，
    // onDismissFinished 回调里再执行真正的状态变更。
    var eulaDialogVisible by remember { mutableStateOf(true) }
    var pendingEulaAction by remember { mutableStateOf<() -> Unit>({ }) }

    // 更新通道：0=稳定版，1=预览版
    var updateChannel by remember {
        mutableStateOf(UpdatePreferences.getChannel(context))
    }

    // 日志导出：直接读 `/sdcard/Android/media/<游戏包>/`（模块 App 持 AFA 跨包直读），
    // **无「确保游戏在运行中」确认弹窗、无"等待新鲜日志"门控**（2026-09-14 定案）：
    // 旧门控在游戏闪退/无法运行时恰好阻止导出崩溃现场，与"导出日志用于排查"
    // 的目的背道而驰。media 直读与游戏是否运行、广播是否可达无关。
    var exportLoadingVisible by remember { mutableStateOf(false) }
    var exportLoadingShownAt by remember { mutableStateOf(0L) }

    /**
     * 转圈遮罩最短显示 800ms：导出链通常 <1s，遮罩一闪而过等于没看到。
     * ⚠️ 局部函数须先声明后使用，故置于 startExport 之前。
     */
    suspend fun holdMinLoadingThenHide() {
        val elapsed = System.currentTimeMillis() - exportLoadingShownAt
        if (elapsed < 800) kotlinx.coroutines.delay(800 - elapsed)
        exportLoadingVisible = false
    }

    fun startExport() {
        exportLoadingVisible = true
        exportLoadingShownAt = System.currentTimeMillis()
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                LogExporter.export(context)
            }
            holdMinLoadingThenHide()
            if (uri != null) {
                LogExporter.share(context, uri)
            } else {
                Toast.makeText(context, "导出失败，未找到日志文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 围场服务器：选项菜单（0=CAMDA 默认，1=自定义）+ 自定义时的输入框。
    // 自定义输入防抖保存（stop 输入 800ms 落库），切回默认立即保存。
    val paddockServerCustom = uiState.paddockServer.isNotBlank()
    var paddockServerSelection by remember(paddockServerCustom) {
        mutableStateOf(if (paddockServerCustom) 1 else 0)
    }
    var paddockServerInput by remember { mutableStateOf(TextFieldValue(uiState.paddockServer)) }
    var paddockServerSaveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val paddockServerItems = remember {
        listOf(
            DropdownItem(text = "CAMDA（默认）"),
            DropdownItem(text = "自定义")
        )
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

                        // ── 组 2: 日志（日志已强制开启，无开关；仅保留导出入口）──
                        Card(modifier = Modifier.fillMaxWidth()) {
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
                                    startExport()
                                }
                            )
                        }

                        // ── 组 2.5: 围场服务器（S4，2026-09-01 改选项菜单）──
                        Card(modifier = Modifier.fillMaxWidth()) {
                            OverlaySpinnerPreference(
                                items = paddockServerItems,
                                selectedIndex = paddockServerSelection,
                                title = "围场服务器",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Public,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onSelectedIndexChange = { sel ->
                                    paddockServerSelection = sel
                                    if (sel == 0) {
                                        // 切回默认：清输入并立即保存
                                        paddockServerInput = TextFieldValue("")
                                        actions.setPaddockServer("")
                                        Toast.makeText(context, "已恢复默认服务器", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                            AnimatedVisibility(visible = paddockServerSelection == 1) {
                                Column {
                                    TextField(
                                        value = paddockServerInput,
                                        onValueChange = { paddockServerInput = it },
                                        label = "请以 https:// 开头",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(
                                            text = "保存",
                                            onClick = {
                                                paddockServerSaveJob?.cancel()
                                                paddockServerSaveJob = scope.launch { actions.setPaddockServer(paddockServerInput.text.trim()) }
                                                Toast.makeText(context, "重启模块生效", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.textButtonColorsPrimary(),
                                            modifier = Modifier.width(120.dp),
                                        )
                                    }
                                }
                            }
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

    // 导出中转圈：窗口级 Dialog（全屏遮罩盖住导航栏/状态栏，页内 Box 会被
    // pager 裁剪且盖不住底部栏）+ 半透明背景 + 居中转圈；全屏 clickable 消费
    // 一切手势屏蔽底层操作。最短显示 800ms（holdMinLoadingThenHide）。
    if (exportLoadingVisible) {
        Dialog(
            onDismissRequest = { },  // 不可点外关闭——导出进行中
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { }
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                InfiniteProgressIndicator()
            }
        }
    }
}
