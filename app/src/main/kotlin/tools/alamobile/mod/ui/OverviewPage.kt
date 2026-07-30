package tools.alamobile.mod.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
// matchParentSize not used, using fillMaxSize instead
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Phone
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.alamobile.mod.BuildConfig
import tools.alamobile.mod.LsposedStatus
import tools.alamobile.mod.util.openExternalUrl
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun OverviewPage(bottomBarHeight: Dp = 0.dp) {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = "Ala Mobile Tool",
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
            // 底部留出底栏高度，否则最后一个 item 会被 NavigationBar 挡住。
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + bottomBarHeight
            ),
            overscrollEffect = null
        ) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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

@Composable
private fun ActivationCard() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(LsposedStatus.evaluate(context, awaitModuleLoad = true)) }
    var showNonRootDialog by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()

    // 进入页面时刷新一次（不轮询）：覆盖弹窗选完后的回写、或从设置页清除标记回来。
    LaunchedEffect(Unit) {
        status = LsposedStatus.evaluate(context, awaitModuleLoad = false)
    }

    // 配色照搬 KernelSU HomeMiuix 的 StatusCard：已激活用绿色调强调底
    // （深色 #1A3825 / 浅色 #DFFAE4）+ 绿勾 #36D167；未激活对称用红色调
    // 强调底（深色 #3D1A1A / 浅色 #FAE4E4）+ 红叉 #FF5252。两态都是强调底，
    // 只是色相绿/红对称，不出现默认 surface 的纯黑/灰。
    val activated = status != LsposedStatus.Status.INACTIVE
    val cardColor = if (activated) {
        if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    } else {
        if (isDark) Color(0xFF3D1A1A) else Color(0xFFFAE4E4)
    }
    val textColor = if (isDark) Color.White else MiuixTheme.colorScheme.onSurface
    val descColor = if (isDark) Color(0xCCFFFFFF) else MiuixTheme.colorScheme.onSurfaceVariantSummary

    val titleText = if (activated) "已激活" else "未激活"
    val descText = when (status) {
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
            // 未激活才弹窗（已激活的两种状态点击无操作）。
            if (status == LsposedStatus.Status.INACTIVE) {
                showNonRootDialog = true
            }
        },
        showIndication = true,
        pressFeedbackType = if (activated) PressFeedbackType.Tilt else PressFeedbackType.Sink
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
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
                status = LsposedStatus.evaluate(context, awaitModuleLoad = false)
                showNonRootDialog = false
            },
            onDismiss = {
                // 选"否" → 保持未激活，不写标记。
                showNonRootDialog = false
            }
        )
    }
}

@Composable
private fun NonRootConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // 用 miuix 官方 OverlayDialog（照搬 miuix example CardSection 的 LongPressHoldDownCardDemo
    // 里 dialog 用法）：title/summary 自动排版，content 放底部两个 TextButton。
    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true,
        title = "您是否安装了 LSPatch、NPatch 或 FPA 等免 Root LSPosed 框架？",
        onDismissRequest = onDismiss,
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = "否",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = "是",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
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
            modifier = Modifier
                .fillMaxWidth(),
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
                        imageVector = Icons.Rounded.Phone,
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
                        imageVector = Icons.Rounded.Info,
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
