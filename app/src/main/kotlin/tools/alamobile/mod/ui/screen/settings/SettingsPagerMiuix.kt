package tools.alamobile.mod.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import top.yukonga.miuix.kmp.overlay.OverlayDialog
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
    // OverlayDialog show 驱动退出动画：关闭时先把 eulaDialogVisible 翻 false 触发动画，
    // onDismissFinished 回调里再执行真正的状态变更。
    var eulaDialogVisible by remember { mutableStateOf(true) }
    var pendingEulaAction by remember { mutableStateOf<() -> Unit>({ }) }

    // 更新通道：0=稳定版，1=预览版
    var updateChannel by remember {
        mutableStateOf(UpdatePreferences.getChannel(context))
    }

    // 「确保游戏在运行中」确认弹窗（2026-09-08 V5）：点导出必弹（纯文字引导，
    // 无权限探活——UsageStats/广播往返方案均被实机证伪）。
    // 两变量拆分（CLAUDE.md 弹窗契约）：mounted 控制挂载，dialogShow 驱动
    // show 参数——关闭时先翻 dialogShow=false 播退出动画，onDismissFinished
    // 里才翻 mounted=false 摘除。单变量混用两职责会跳过退出动画（用户强调
    // 过多次：弹窗必须有动画）。
    // 点"继续导出" → 关弹窗 → 转圈遮罩（屏蔽操作）→ awaitFreshLogs 等 3s：
    // 游戏推来新日志（缓存 mtime 更新）→ 继续导出+分享；3s 没等到 → 收圈 +
    // Toast「请先启动游戏！」，**不导出**（禁止回落旧缓存，2026-09-08 定案）。
    var gameDialogMounted by remember { mutableStateOf(false) }
    var gameDialogShow by remember { mutableStateOf(false) }
    var exportLoadingVisible by remember { mutableStateOf(false) }
    var exportLoadingShownAt by remember { mutableStateOf(0L) }

    /**
     * 转圈遮罩最短显示 800ms：游戏活着时推送+导出链 <1s 完成，遮罩一闪而过
     * 等于没看到（用户反馈"没有转圈动画"）。成功路径也兜住最短时长，让
     * "正在导出"的状态可感知；失败路径（3s 超时）天然超时长。
     * ⚠️ 局部函数须先声明后使用，故置于 startExportWithLoading 之前。
     */
    suspend fun holdMinLoadingThenHide() {
        val elapsed = System.currentTimeMillis() - exportLoadingShownAt
        if (elapsed < 800) kotlinx.coroutines.delay(800 - elapsed)
        exportLoadingVisible = false
    }

    fun startExportWithLoading() {
        exportLoadingVisible = true
        exportLoadingShownAt = System.currentTimeMillis()
        scope.launch {
            val fresh = LogExporter.awaitFreshLogs(context)
            if (!fresh) {
                holdMinLoadingThenHide()
                Toast.makeText(context, "请先启动游戏！", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = withContext(Dispatchers.IO) {
                LogExporter.export(context)
            }
            holdMinLoadingThenHide()
            if (uri != null) {
                LogExporter.share(context, uri)
            } else {
                Toast.makeText(context, "请先启动游戏！", Toast.LENGTH_SHORT).show()
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
                                    gameDialogMounted = true
                                    gameDialogShow = true
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

    // 「确保游戏在运行中」确认弹窗：标题居中粗体、正文左对齐（弹窗排版铁律）。
    // 左灰"取消"右蓝"继续导出"。
    if (gameDialogMounted) {
        OverlayDialog(
            show = gameDialogShow,
            onDismissRequest = { gameDialogShow = false },
            onDismissFinished = { gameDialogMounted = false },
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "确保游戏在运行中",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                    Text(
                        text = "请确认游戏已在运行，否则无法导出日志。启动游戏并至少等待 15 秒，再返回此处导出日志。",
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { gameDialogShow = false },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(20.dp))
                        TextButton(
                            text = "继续导出",
                            onClick = {
                                gameDialogShow = false
                                startExportWithLoading()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
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
