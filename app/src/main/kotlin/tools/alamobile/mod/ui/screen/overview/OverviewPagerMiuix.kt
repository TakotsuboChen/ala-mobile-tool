package tools.alamobile.mod.ui.screen.overview

import android.os.Build
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tools.alamobile.mod.BuildConfig
import tools.alamobile.mod.EulaManager
import tools.alamobile.mod.LsposedStatus
import tools.alamobile.mod.ui.viewmodel.ConfigViewModel
import tools.alamobile.mod.util.openExternalUrl
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
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = tools.alamobile.mod.ui.theme.LocalEnableBlur.current
    val backdrop = tools.alamobile.mod.ui.util.rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            tools.alamobile.mod.ui.util.BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = "Ala Mobile Tool",
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
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + bottomInnerPadding
                ),
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ActivationCard()
                        DeviceInfoCard()
                        LinksCard()
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivationCard() {
    val context = LocalContext.current
    // 照搬 KernelSU HomeScreen：初始状态用 null（不阻塞），LaunchedEffect 里异步加载。
    // 之前 remember{ LsposedStatus.evaluate(awaitService=false) } 虽然不做 3s 轮询，
    // 但仍然在主线程做文件存在性检查 + SharedPreferences 读取，首次组合时会阻塞。
    var status by remember { mutableStateOf<LsposedStatus.Status?>(null) }
    var showNonRootDialog by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()

    // 所有激活状态检测都在 IO 线程：awaitService=false 快速返回 + awaitService=true 轮询升级。
    LaunchedEffect(Unit) {
        // 先快速返回一个初始值（IO 线程），再异步升级到准确值
        val initial = withContext(Dispatchers.IO) {
            LsposedStatus.evaluate(context, awaitService = false)
        }
        status = initial

        val evaluated = withContext(Dispatchers.IO) {
            LsposedStatus.evaluate(context, awaitService = true)
        }
        status = evaluated
        if (evaluated == LsposedStatus.Status.INACTIVE) {
            showNonRootDialog = true
        }
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
        LsposedStatus.Status.NONROOT -> "模块已通过 Non-root LSPosed 加载"
        LsposedStatus.Status.INACTIVE -> "点击确认是否使用了免 Root 框架"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp),
        colors = CardDefaults.defaultColors(color = cardColor),
        onClick = {
            if (status == LsposedStatus.Status.INACTIVE) {
                showNonRootDialog = true
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
            onConfirm = {
                LsposedStatus.confirmNonRoot(context)
                status = LsposedStatus.evaluate(context, awaitService = false)
                showNonRootDialog = false
            },
            onDismiss = { showNonRootDialog = false }
        )
    }
}

@Composable
private fun NonRootConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    OverlayDialog(
        show = true,
        title = "您是否安装了 LSPatch、NPatch 或 FPA 等免 Root LSPosed 框架？",
        onDismissRequest = onDismiss,
        content = {
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
private fun LinksCard() {
    val context = LocalContext.current

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
                onClick = { openExternalUrl(context, "https://github.com/TakotsuboChen/ala-mobile-tool/releases") }
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
                onClick = { openExternalUrl(context, "https://qun.qq.com/universal-share/share?ac=1&authKey=V0nuKHg0u%2BZKVi/jgDReAiZSCQdbMb0yMwaOSV49gejQWRtdz%2BG4G6eQQgWyFOJB&busi_data=eyJncm91cENvZGUiOiI3NTc5NDA3MDgiLCJ0b2tlbiI6IjVzRjZTTWpLckJIRExvRTk3K0QzVzJGK2N4QURRM2RwRjJWNkw0L29wcG9ocjI1NXo5T1hLZ2FJVkZXZkhlMVAiLCJ1aW4iOiIxMjU5OTc2NTIwIn0=&data=x1JvsLJUAovAdpfNmLQpuTN_-yGbUrMfCJ1VSQqD-QbIzj9-ZLiRKNEHNbJXpokkPhx5cc-RG47HyWYUrPBtTA&svctype=4&tempid=h5_group_info") }
            )
            BasicComponent(
                title = "GitHub 源代码",
                summary = "欢迎 Star",
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
        }
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
