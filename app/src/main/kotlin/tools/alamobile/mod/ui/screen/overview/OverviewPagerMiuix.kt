package tools.alamobile.mod.ui.screen.overview

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VolunteerActivism
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import tools.alamobile.mod.BuildConfig
import tools.alamobile.mod.EulaManager
import tools.alamobile.mod.App
import tools.alamobile.mod.ConnectionState
import tools.alamobile.mod.LsposedStatus
import tools.alamobile.mod.ui.EulaDialog
import tools.alamobile.mod.ui.SupportDialog
import tools.alamobile.mod.ui.UpdateDialog
import tools.alamobile.mod.ui.viewmodel.ConfigViewModel
import tools.alamobile.mod.update.UpdateCheckResult
import tools.alamobile.mod.update.UpdateChecker
import tools.alamobile.mod.update.UpdateInfo
import tools.alamobile.mod.update.UpdatePreferences
import tools.alamobile.mod.util.COEXISTENCE_PKG
import tools.alamobile.mod.util.GameVersionStatus
import tools.alamobile.mod.util.OFFICIAL_PKG
import tools.alamobile.mod.util.checkGameVersion
import tools.alamobile.mod.util.openExternalUrl
import tools.alamobile.mod.util.openQqGroup
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 照搬 KernelSU `HomePagerMiuix`（HomeMiuix.kt:81）结构：
 * Scaffold + BlurredBar + TopAppBar + LazyColumn(overScrollVertical + scrollEndHaptic) + layerBackdrop。
 *
 * 三个 Card 保留 Ala Mobile 业务：ActivationCard / DeviceInfoCard / LinksCard。
 * InfoRow 保留手写（title/value 对齐的 Row），因为它不是 preference 项，
 * 是纯展示信息——KernelSU 的 InfoCard 也是手写 Row+Text。
 */
@Composable
fun OverviewPagerMiuix(
    uiState: tools.alamobile.mod.ui.viewmodel.ConfigUiState,
    actions: ConfigViewModel,
    bottomInnerPadding: Dp,
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = tools.alamobile.mod.ui.theme.LocalEnableBlur.current
    val backdrop = tools.alamobile.mod.ui.util.rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    // EULA 启动门控：未同意当前版本协议时，在概览页 Scaffold popupHost 里先渲染 EULA 弹窗，
    // 优先级高于激活状态弹窗（NonRootConfirmDialog）。点「同意」后才放行激活弹窗。
    var eulaAccepted by remember {
        mutableStateOf(EulaManager.isAccepted(context))
    }
    // OverlayDialog show 驱动退出动画：关闭时先把 eulaDialogVisible 翻 false 触发动画，
    // onDismissFinished 回调里再执行真正的状态变更。
    var eulaDialogVisible by remember { mutableStateOf(true) }
    var pendingEulaAction by remember { mutableStateOf<() -> Unit>({ }) }

    // ── 更新检查状态 ──
    // 弹窗优先级：EULA > 激活 > 更新。更新弹窗只在 EULA 已同意时才触发。
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateDialogVisible by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ── 游戏版本检测状态 ──
    // 每次启动 ConfigActivity 都自动检测官版/共存版安装情况与版本适配。
    // null 表示检测中（胶囊不显示，避免闪烁）。
    var officialStatus by remember { mutableStateOf<GameVersionStatus?>(null) }
    var coexistenceStatus by remember { mutableStateOf<GameVersionStatus?>(null) }

    // 启动时自动检查更新 + 清理旧 APK
    LaunchedEffect(Unit) {
        // 清理旧版本 APK（用户已安装新版本时）
        UpdatePreferences.cleanupOldApkIfNeeded(context, BuildConfig.VERSION_CODE)

        // 游戏版本检测（IO 线程，PackageManager 查询）
        val official = withContext(Dispatchers.IO) { checkGameVersion(context, OFFICIAL_PKG) }
        val coexistence = withContext(Dispatchers.IO) { checkGameVersion(context, COEXISTENCE_PKG) }
        officialStatus = official
        coexistenceStatus = coexistence

        // 等 EULA 同意后再检查更新
        if (eulaAccepted) {
            isCheckingUpdate = true
            val result = UpdateChecker.checkLatest(UpdatePreferences.getChannel(context))
            isCheckingUpdate = false
            if (result is UpdateCheckResult.HasUpdate) {
                val info = result.info
                if (info.latestVersionCode != null &&
                    info.latestVersionCode > BuildConfig.VERSION_CODE
                ) {
                    // 检查是否被用户跳过
                    val skipped = UpdatePreferences.getSkippedVersionCode(context)
                    if (skipped != info.latestVersionCode) {
                        updateInfo = info
                        showUpdateDialog = true
                        updateDialogVisible = true
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            tools.alamobile.mod.ui.util.BlurredBar(backdrop) {
                Box {
                    // TopAppBar 在底层，保持原有布局（含内部状态栏 inset 处理）不受影响。
                    TopAppBar(
                        color = barColor,
                        title = "Ala Mobile Tool",
                        scrollBehavior = scrollBehavior,
                    )
                    // 游戏版本检测胶囊行：叠加在 TopAppBar 之上，位于大标题上方空白处，左端对齐。
                    // 动态计算 top offset：
                    //   可用空间 = CollapsedHeight(52dp) - 胶囊高度
                    //   topOffset = 状态栏高度 + 可用空间 / 2
                    // 读取设备实际状态栏高度，适配所有设备。
                    //
                    // 收缩/恢复动画用 Animatable 做非对称过渡，避免与小标题 spring 动画重叠：
                    //   下滑（fraction > 0）：snapTo(0) 即时隐藏，不给小标题重叠的机会。
                    //   上滑恢复（fraction == 0）：延迟 350ms 等小标题 spring 完全淡出后 animateTo(1)。
                    //     fraction 持续变化时 delay 被反复取消，直到 fraction 停在 0 后 delay 才等完。
                    if (officialStatus != null && coexistenceStatus != null) {
                        val fraction = scrollBehavior.state.collapsedFraction
                        val capsuleAlpha = remember { Animatable(1f) }
                        LaunchedEffect(fraction) {
                            if (fraction > 0f) {
                                // 下滑收缩：即时隐藏
                                capsuleAlpha.snapTo(0f)
                            } else {
                                // 上滑恢复（fraction == 0）：延迟后渐显，等小标题淡出
                                kotlinx.coroutines.delay(350)
                                capsuleAlpha.animateTo(1f, spring(dampingRatio = 1f, stiffness = 300f))
                            }
                        }
                        val density = LocalDensity.current
                        val statusBarHeightPx = WindowInsets.statusBars.getTop(density)
                        val collapsedHeightPx = with(density) { 52.dp.roundToPx() }
                        val capsuleHeightPx = with(density) { 24.dp.roundToPx() }
                        val availableSpace = (collapsedHeightPx - capsuleHeightPx).coerceAtLeast(0)
                        val topOffsetPx = statusBarHeightPx + availableSpace / 2
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp)
                                .offset { IntOffset(0, topOffsetPx) }
                                .graphicsLayer { alpha = capsuleAlpha.value },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            VersionCapsule(
                                label = "官版",
                                status = officialStatus!!
                            )
                            VersionCapsule(
                                label = "共存版",
                                status = coexistenceStatus!!
                            )
                        }
                    }
                }
            }
        },
        popupHost = {
            // EULA 弹窗先于 MiuixPopupHost 渲染：zIndex 天然高于激活弹窗，
            // 未同意时盖在激活弹窗之上；点同意后 eulaAccepted=true，EULA 消失放行激活弹窗。
            if (!eulaAccepted) {
                EulaDialog(
                    sections = EulaManager.EULA_SECTIONS,
                    footer = EulaManager.EULA_FOOTER,
                    show = eulaDialogVisible,
                    onAccept = {
                        // 先翻 false 触发退出动画，动画结束后 onDismissFinished 执行真正 accept
                        pendingEulaAction = {
                            EulaManager.accept(context)
                            eulaAccepted = true
                        }
                        eulaDialogVisible = false
                    },
                    onExit = {
                        pendingEulaAction = {
                            (context as? android.app.Activity)?.finish()
                        }
                        eulaDialogVisible = false
                    },
                    onDismissFinished = {
                        pendingEulaAction()
                    }
                )
            }
            MiuixPopupHost()
            // 更新弹窗排最后，zIndex 最低，确保不与 EULA 和激活弹窗打架。
            if (showUpdateDialog && updateInfo != null) {
                UpdateDialog(
                    show = updateDialogVisible,
                    updateInfo = updateInfo!!,
                    onRequestClose = { updateDialogVisible = false },
                    onDismissFinished = {
                        showUpdateDialog = false
                    },
                    onSkipped = {
                        updateInfo?.latestVersionCode?.let {
                            UpdatePreferences.skipVersion(context, it)
                        }
                    }
                )
            }
        },
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
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ActivationCard(eulaAccepted = eulaAccepted)
                        DeviceInfoCard()
                        LinksCard(
                            onCheckUpdate = {
                                if (!isCheckingUpdate && eulaAccepted) {
                                    isCheckingUpdate = true
                                    android.widget.Toast.makeText(
                                        context,
                                        "正在检查更新...",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    scope.launch {
                                        val result = UpdateChecker.checkLatest(UpdatePreferences.getChannel(context))
                                        isCheckingUpdate = false
                                        when (result) {
                                            is UpdateCheckResult.Failed -> {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "检查更新失败，请稍后重试",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            is UpdateCheckResult.NoUpdate -> {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "当前已是最新版本",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            is UpdateCheckResult.HasUpdate -> {
                                                val info = result.info
                                                if (info.latestVersionCode == null ||
                                                    info.latestVersionCode <= BuildConfig.VERSION_CODE
                                                ) {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "当前已是最新版本",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    // 手动检查：不管是否跳过，有新版本就弹窗
                                                    updateInfo = info
                                                    showUpdateDialog = true
                                                    updateDialogVisible = true
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivationCard(eulaAccepted: Boolean) {
    val context = LocalContext.current
    // 照搬 KernelSU HomeScreen：初始状态用 null（不阻塞），LaunchedEffect 里异步加载。
    // evaluate() 是纯同步快速检测（文件存在性检查），首次组合时在 IO 线程执行。
    var status by remember { mutableStateOf<LsposedStatus.Status?>(null) }
    // NPatch 管理器是否已安装。未安装时不允许弹 Non-root 确认弹窗，
    // 点击卡片改为 Toast 提示"未检测到 LSPosed 或 NPatch 框架"。
    var npatchInstalled by remember { mutableStateOf(false) }
    var showNonRootDialog by remember { mutableStateOf(false) }
    // OverlayDialog 的 show 驱动退出动画：关闭时先把 dialogVisible 翻 false 触发动画，
    // onDismissFinished 回调里再清 showNonRootDialog（真正移除 composable）+ 执行副作用。
    var dialogVisible by remember { mutableStateOf(false) }
    // 区分关闭原因，onDismissFinished 里据此执行不同副作用。
    var pendingAction by remember { mutableStateOf<() -> Unit>({ }) }
    // 首次检测完成标记：LaunchedEffect(Unit) 顺序执行首次检测后置 true，
    // LaunchedEffect(connectionState) 据此跳过首次（避免重复弹窗）。
    var firstCheckDone by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()

    // 首次检测 + 弹窗：顺序执行（恢复原来的弹窗逻辑），用 connectionState
    // 等待 service 稳定，替代原来的 evaluate(awaitService=true) 3s 轮询。
    // npatchInstalled 在弹窗前已设置，无竞态。
    LaunchedEffect(Unit) {
        // NPatch 管理器安装检测（IO 线程，PackageManager 查询）。
        npatchInstalled = withContext(Dispatchers.IO) {
            LsposedStatus.isNpatchInstalled(context)
        }
        // 等 connectionState 稳定（Connecting → Connected/Disconnected），最多 2s。
        // 参照 AdClose AppRepository: withTimeoutOrNull { connectionState.first { !is Connecting } }。
        // 协程挂起不阻塞线程，service 在 2s 内绑上则返回 Connected，超时则 null。
        // 等 connectionState 稳定（service 绑上或 2s 超时），然后 evaluate 检查 App.xposedService。
        // evaluate 内部判断 frameworkName=="LSPosed" → LSPOSED，NPatch → 走手动确认路径。
        withTimeoutOrNull(2000L) {
            App.connectionState.first { it !is ConnectionState.Connecting }
        }
        val evaluated = withContext(Dispatchers.IO) { LsposedStatus.evaluate(context) }
        status = evaluated
        firstCheckDone = true
        // 弹窗条件与原逻辑一致：INACTIVE + EULA 已同意 + NPatch 已安装。
        if (evaluated == LsposedStatus.Status.INACTIVE && eulaAccepted && npatchInstalled) {
            showNonRootDialog = true
            dialogVisible = true
        }
    }

    // 事件驱动：service 后续变化时刷新 status + 弹窗。
    // firstCheckDone 守护：首次检测由 LaunchedEffect(Unit) 负责，这里跳过首次。
    val connectionState by App.connectionState.collectAsState()
    LaunchedEffect(connectionState) {
        if (!firstCheckDone) return@LaunchedEffect
        when (val state = connectionState) {
            is ConnectionState.Connected -> {
                // service 绑上/升级 → 重新 evaluate（检查 App.xposedService）
                val evaluated = withContext(Dispatchers.IO) { LsposedStatus.evaluate(context) }
                status = evaluated
                if (evaluated == LsposedStatus.Status.LSPOSED) {
                    showNonRootDialog = false
                    dialogVisible = false
                } else if (evaluated == LsposedStatus.Status.INACTIVE && eulaAccepted && npatchInstalled) {
                    showNonRootDialog = true
                    dialogVisible = true
                }
            }
            is ConnectionState.Disconnected -> {
                val evaluated = withContext(Dispatchers.IO) { LsposedStatus.evaluate(context) }
                status = evaluated
                if (evaluated == LsposedStatus.Status.INACTIVE && eulaAccepted && npatchInstalled) {
                    showNonRootDialog = true
                    dialogVisible = true
                }
            }
            is ConnectionState.Connecting -> { }
        }
    }

    // EULA 同意后补弹：eulaAccepted 从 false→true 时若仍 INACTIVE，触发弹窗。
    LaunchedEffect(eulaAccepted) {
        if (eulaAccepted && status == LsposedStatus.Status.INACTIVE && npatchInstalled) {
            showNonRootDialog = true
            dialogVisible = true
        }
    }

    // onResume 重新检测：后台缓存恢复时 service 状态可能已变。
    // 只刷新 status，不触发弹窗（弹窗由 ConnectionState 事件驱动）。
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    val evaluated = withContext(Dispatchers.IO) {
                        LsposedStatus.evaluate(context)
                    }
                    if (evaluated != status) {
                        status = evaluated
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // status 为 null 时显示加载态，避免 null 检查导致的颜色闪烁。
    val activated = status != null && status != LsposedStatus.Status.INACTIVE
    val cardColor = if (activated) {
        if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    } else {
        if (isDark) Color(0xFF3D1A1A) else Color(0xFFFAE4E4)
    }
    val textColor = if (isDark) Color.White else MiuixTheme.colorScheme.onSurface
    val descColor = if (isDark) Color(0xCCFFFFFF) else MiuixTheme.colorScheme.onSurfaceVariantSummary

    val titleText = if (status == null) "检测中..." else if (activated) "已激活" else "未激活"
    val descText = when (status) {
        null -> "正在检测模块激活状态"
        LsposedStatus.Status.LSPOSED -> "模块已通过 LSPosed 加载"
        LsposedStatus.Status.NONROOT -> "模块已通过 NPatch 免 Root 框架加载"
        LsposedStatus.Status.INACTIVE -> "点击确认是否使用了免 Root 框架"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp),
        colors = CardDefaults.defaultColors(color = cardColor),
        onClick = {
            if (status == LsposedStatus.Status.INACTIVE) {
                if (npatchInstalled) {
                    showNonRootDialog = true
                    dialogVisible = true
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "未检测到 LSPosed 或 NPatch 框架，请确认是否已安装",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        },
        showIndication = true,
        pressFeedbackType = if (activated) PressFeedbackType.Tilt else PressFeedbackType.Sink
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 右下角大图标，超出边界被裁剪
            Icon(
                imageVector = if (activated) Icons.Rounded.CheckCircleOutline else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 35.dp, y = 25.dp),
                tint = if (activated) Color(0xFF36D167) else Color(0xFFFF5252)
            )

            // 左上角文本内容
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopStart)
            ) {
                Text(
                    text = titleText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = descText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = descColor
                )
            }
        }
    }

    if (showNonRootDialog) {
        NonRootConfirmDialog(
            show = dialogVisible,
            onConfirm = {
                // 先把 visible 翻 false 触发退出动画，动画结束后 onDismissFinished 执行真正逻辑
                pendingAction = {
                    LsposedStatus.confirmNonRoot(context)
                    // 重新检测：若 LSPosed service 已绑上（ConnectionState.Connected），
                    // LaunchedEffect(connectionState) 会优先刷新到 LSPOSED 并清掉刚写的
                    // nonroot flag——这正是"LSPosed 状态高于一切"的语义。
                    status = LsposedStatus.evaluate(context)
                }
                dialogVisible = false
            },
            onDismiss = {
                pendingAction = { }
                dialogVisible = false
            },
            onDismissFinished = {
                showNonRootDialog = false
                pendingAction()
            }
        )
    }
}

@Composable
private fun NonRootConfirmDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit
) {
    OverlayDialog(
        show = show,
        title = "NPatch 作用域确认",
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 正文左对齐，与 EulaDialog 的条款正文排版一致。
                Text(
                    text = "您是否已经在 NPatch 管理器中对游戏开启了本模块的作用域？",
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        text = "否",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        text = "是",
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
private fun DeviceInfoCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "设备信息",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            InfoRow(title = "版本名称", value = BuildConfig.VERSION_NAME)
            InfoRow(title = "版本号", value = BuildConfig.VERSION_CODE.toString())
            InfoRow(title = "安卓版本", value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoRow(title = "厂商", value = Build.MANUFACTURER)
            InfoRow(title = "型号", value = Build.MODEL)
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, fontSize = 15.sp)
    }
}

@Composable
private fun LinksCard(
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current

    // 支持开发捐赠弹窗状态
    var showSupportDialog by remember { mutableStateOf(false) }
    var supportDialogVisible by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            BasicComponent(
                title = "检查更新",
                summary = "点击检查 GitHub Releases",
                insideMargin = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                },
                onClick = onCheckUpdate
            )
            BasicComponent(
                title = "QQ 群",
                summary = "点击加入交流群",
                insideMargin = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                startAction = {
                    Icon(
                        imageVector = QqMark,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                },
                onClick = {
                    openQqGroup(
                        context = context,
                        groupCode = "757940708",
                        fallbackUrl = "https://qun.qq.com/universal-share/share?ac=1&authKey=V0nuKHg0u%2BZKVi/jgDReAiZSCQdbMb0yMwaOSV49gejQWRtdz%2BG4G6eQQgWyFOJB&busi_data=eyJncm91cENvZGUiOiI3NTc5NDA3MDgiLCJ0b2tlbiI6IjVzRjZTTWpLckJIRExvRTk3K0QzVzVzJGK2N4QURRM2RwRjJWNkw0L29wcG9ocjI1NXo5T1hLZ2FJVkZXZkhlMVAiLCJ1aW4iOiIxMjU5OTc2NTIwIn0=&data=x1JvsLJUAovAdpfNmLQpuTN_-yGbUrMfCJ1VSQqD-QbIzj9-ZLiRKNEHNbJXpokkPhx5cc-RG47HyWYUrPBtTA&svctype=4&tempid=h5_group_info",
                    )
                }
            )
            BasicComponent(
                title = "GitHub 源代码",
                summary = "欢迎 Star、提交 Issue 与 PR",
                insideMargin = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                startAction = {
                    Icon(
                        imageVector = GithubMark,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                },
                onClick = { openExternalUrl(context, "https://github.com/TakotsuboChen/ala-mobile-tool") }
            )
            BasicComponent(
                title = "支持开发",
                summary = "向开发者捐赠以表示支持",
                insideMargin = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.VolunteerActivism,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                },
                onClick = {
                    showSupportDialog = true
                    supportDialogVisible = true
                }
            )
        }
    }

    if (showSupportDialog) {
        SupportDialog(
            show = supportDialogVisible,
            onRequestClose = { supportDialogVisible = false },
            onDismissFinished = {
                showSupportDialog = false
            }
        )
    }
}

// ── 内嵌 SVG path → ImageVector ──
// compose-ui 的 PathParser 直接吃 SVG d 字符串（含 M/m/c/a/z 全命令），转成 PathNode 列表；
// 再在 path() DSL 的 PathBuilder lambda 里按 node 类型分发到对应方法。
// path 数据来自 simple-icons（CC0），24×24 viewBox，与 Material 图标坐标系一致。

private fun svgIcon(name: String, svgPath: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            PathParser().parsePathString(svgPath).toNodes().forEach { node ->
                when (node) {
                    is PathNode.MoveTo -> moveTo(node.x, node.y)
                    is PathNode.LineTo -> lineTo(node.x, node.y)
                    is PathNode.RelativeMoveTo -> moveToRelative(node.dx, node.dy)
                    is PathNode.RelativeLineTo -> lineToRelative(node.dx, node.dy)
                    is PathNode.HorizontalTo -> horizontalLineTo(node.x)
                    is PathNode.VerticalTo -> verticalLineTo(node.y)
                    is PathNode.RelativeHorizontalTo -> horizontalLineToRelative(node.dx)
                    is PathNode.RelativeVerticalTo -> verticalLineToRelative(node.dy)
                    is PathNode.CurveTo -> curveTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
                    is PathNode.RelativeCurveTo -> curveToRelative(node.dx1, node.dy1, node.dx2, node.dy2, node.dx3, node.dy3)
                    is PathNode.QuadTo -> quadTo(node.x1, node.y1, node.x2, node.y2)
                    is PathNode.RelativeQuadTo -> quadToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
                    is PathNode.ReflectiveCurveTo -> reflectiveCurveTo(node.x1, node.y1, node.x2, node.y2)
                    is PathNode.RelativeReflectiveCurveTo -> reflectiveCurveToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
                    is PathNode.ReflectiveQuadTo -> reflectiveQuadTo(node.x, node.y)
                    is PathNode.RelativeReflectiveQuadTo -> reflectiveQuadToRelative(node.dx, node.dy)
                    is PathNode.ArcTo -> arcTo(node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta, node.isMoreThanHalf, node.isPositiveArc, node.arcStartX, node.arcStartY)
                    is PathNode.RelativeArcTo -> arcToRelative(node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta, node.isMoreThanHalf, node.isPositiveArc, node.arcStartDx, node.arcStartDy)
                    is PathNode.Close -> close()
                }
            }
        }
    }.build()

// GitHub mark（Octocat）。simple-icons GitHub path（24×24）。
val GithubMark: ImageVector = svgIcon(
    "GithubMark",
    "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
)

// QQ 企鹅 logo。simple-icons QQ path（24×24）。
val QqMark: ImageVector = svgIcon(
    "QqMark",
    "M21.395 15.035a40 40 0 0 0-.803-2.264l-1.079-2.695c.001-.032.014-.562.014-.836C19.526 4.632 17.351 0 12 0S4.474 4.632 4.474 9.241c0 .274.013.804.014.836l-1.08 2.695a39 39 0 0 0-.802 2.264c-1.021 3.283-.69 4.643-.438 4.673.54.065 2.103-2.472 2.103-2.472 0 1.469.756 3.387 2.394 4.771-.612.188-1.363.479-1.845.835-.434.32-.379.646-.301.778.343.578 5.883.369 7.482.189 1.6.18 7.14.389 7.483-.189.078-.132.132-.458-.301-.778-.483-.356-1.233-.646-1.846-.836 1.637-1.384 2.393-3.302 2.393-4.771 0 0 1.563 2.537 2.103 2.472.251-.03.581-1.39-.438-4.673"
)
