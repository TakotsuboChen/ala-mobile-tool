package tools.alamobile.mod

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.alamobile.mod.BuildConfig
import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.config.ModConfig.PedalCurve
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class ConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkTheme = isSystemInDarkTheme()
            MiuixTheme(
                colors = if (darkTheme) darkColorScheme() else lightColorScheme()
            ) {
                ConfigScreen(onFinish = { finish() })
            }
        }
    }
}

private enum class AppTab {
    OVERVIEW, CONFIGURE, SETTINGS
}

@Composable
private fun ConfigScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { ModConfig.read(context) }

    var selectedTab by remember { mutableStateOf(AppTab.OVERVIEW) }

    var enableControlReplacement by remember { mutableStateOf(settings.enableControlReplacement) }
    var enableAutoDrs by remember { mutableStateOf(settings.enableAutoDrs) }
    var showOverlay by remember { mutableStateOf(settings.showOverlay) }
    var disableAutoGear by remember { mutableStateOf(settings.disableAutoGear) }
    var deadzone by remember { mutableFloatStateOf(settings.pedalDeadzone) }
    var transition by remember { mutableFloatStateOf(settings.pedalTransition) }
    var curve by remember { mutableStateOf(settings.pedalCurve) }
    var logEnabled by remember { mutableStateOf(settings.logEnabled) }

    val saveHandler = remember { Handler(Looper.getMainLooper()) }
    var saveRunnable: Runnable? = null

    val saveNow: () -> Unit = {
        saveRunnable?.let { saveHandler.removeCallbacks(it) }
        val runnable = Runnable {
            ModConfig.write(
                context,
                ModConfig.Settings(
                    enableControlReplacement = enableControlReplacement,
                    enableAutoDrs = enableAutoDrs,
                    showOverlay = showOverlay,
                    disableAutoGear = disableAutoGear,
                    pedalDeadzone = deadzone,
                    pedalTransition = transition,
                    pedalCurve = curve,
                    logEnabled = logEnabled
                )
            )
        }
        saveRunnable = runnable
        saveHandler.postDelayed(runnable, 300)
    }

    val switchSave: (() -> Unit) -> Unit = { action ->
        action()
        saveNow()
    }

    val sliderSave: (() -> Unit) -> Unit = { action ->
        action()
        saveNow()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.fillMaxWidth()
            ) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.OVERVIEW,
                    onClick = { selectedTab = AppTab.OVERVIEW },
                    icon = Icons.Default.Home,
                    label = "概览"
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.CONFIGURE,
                    onClick = { selectedTab = AppTab.CONFIGURE },
                    icon = Icons.Default.Build,
                    label = "配置"
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.SETTINGS,
                    onClick = { selectedTab = AppTab.SETTINGS },
                    icon = Icons.Default.Settings,
                    label = "设置"
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            when (selectedTab) {
                AppTab.OVERVIEW -> OverviewPage()
                AppTab.CONFIGURE -> ConfigurePage(
                    enableControlReplacement = enableControlReplacement,
                    onEnableControlReplacement = { switchSave { enableControlReplacement = it } },
                    enableAutoDrs = enableAutoDrs,
                    onEnableAutoDrs = { switchSave { enableAutoDrs = it } },
                    showOverlay = showOverlay,
                    onShowOverlay = { switchSave { showOverlay = it } },
                    disableAutoGear = disableAutoGear,
                    onDisableAutoGear = { switchSave { disableAutoGear = it } },
                    deadzone = deadzone,
                    onDeadzone = { sliderSave { deadzone = it } },
                    transition = transition,
                    onTransition = { sliderSave { transition = it } },
                    curve = curve,
                    onCurve = { switchSave { curve = it } }
                )
                AppTab.SETTINGS -> SettingsPage(
                    logEnabled = logEnabled,
                    onLogEnabled = { switchSave { logEnabled = it } }
                )
            }
        }
    }
}

@Composable
private fun OverviewPage() {
    val context = LocalContext.current
    val activated = remember { LsposedStatus.isActivated(context) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Ala Mobile Tool",
            fontSize = 28.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        ActivationCard(activated = activated)
        DeviceInfoCard()
        LinksCard()
    }
}

@Composable
private fun ActivationCard(activated: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = {}
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = if (activated) "已激活" else "未激活", fontSize = 18.sp)
                Text(
                    text = if (activated) "模块已通过 LSPosed 加载" else "请前往 LSPosed Manager 启用本模块",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }
            Icon(
                imageVector = if (activated) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (activated) Color(0xFF4CAF50) else Color(0xFFFF5252)
            )
        }
    }
}

@Composable
private fun DeviceInfoCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "设备信息", fontSize = 16.sp)
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
        Text(text = title, fontSize = 15.sp, color = Color.Gray)
        Text(text = value, fontSize = 15.sp)
    }
}

@Composable
private fun LinksCard() {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LinkItem(
                title = "检查更新",
                summary = "点击检查更新",
                icon = { Icons.Default.Refresh },
                onClick = {
                    tryStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TakotsuboChen/ala-mobile-tool/releases")))
                }
            )
            LinkItem(
                title = "QQ 群",
                summary = "点击加入",
                icon = { Icons.Default.Menu },
                onClick = {
                    val url = "https://qun.qq.com/universal-share/share?ac=1&authKey=V0nuKHg0u%2BZKVi%2FjgDReAiZSCQdbMb0yMwaOSV49gejQWRtdz%2BG4G6eQQgWyFOJB&busi_data=eyJncm91cENvZGUiOiI3NTc5NDA3MDgiLCJ0b2tlbiI6IjVzRjZTTWpLckJIRExvRTk3K0QzVzJGK2N4QURRM2RwRjJWNkw0L29wcG9ocjI1NXo5T1hLZ2FJVkZXZkhlMVAiLCJ1aW4iOiIxMjU5OTc2NTUyMCJ9%3D&data=x1JvsLJUAovAdpfNmLQpuTN_-yGbUrMfCJ1VSQqD-QbIzj9-ZLiRKNEHNbJXpokkPhx5cc-RG47HyWYUrPBtTA&svctype=4&tempid=h5_group_info"
                    tryStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            )
            LinkItem(
                title = "GitHub 源代码",
                summary = "欢迎 Star",
                icon = { Icons.Default.ArrowForward },
                onClick = {
                    tryStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TakotsuboChen/ala-mobile-tool")))
                }
            )
        }
    }
}

private fun tryStartActivity(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun LinkItem(
    title: String,
    summary: String,
    icon: @Composable () -> androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Color.Gray
                )
                Column {
                    Text(text = title, fontSize = 15.sp)
                    Text(text = summary, fontSize = 13.sp, color = Color.Gray)
                }
            }
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color.Gray
            )
        }
    }
}

@Composable
private fun ConfigurePage(
    enableControlReplacement: Boolean,
    onEnableControlReplacement: (Boolean) -> Unit,
    enableAutoDrs: Boolean,
    onEnableAutoDrs: (Boolean) -> Unit,
    showOverlay: Boolean,
    onShowOverlay: (Boolean) -> Unit,
    disableAutoGear: Boolean,
    onDisableAutoGear: (Boolean) -> Unit,
    deadzone: Float,
    onDeadzone: (Float) -> Unit,
    transition: Float,
    onTransition: (Float) -> Unit,
    curve: PedalCurve,
    onCurve: (PedalCurve) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SmallTitle(text = "功能开关")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SwitchRow(
                    title = "踏板覆盖",
                    summary = "用悬浮窗踏板替代游戏默认输入",
                    checked = enableControlReplacement,
                    onCheckedChange = onEnableControlReplacement
                )
                SwitchRow(
                    title = "自动 DRS",
                    summary = "在 DRS 区域自动开启 DRS",
                    checked = enableAutoDrs,
                    onCheckedChange = onEnableAutoDrs
                )
                SwitchRow(
                    title = "显示悬浮窗",
                    summary = "在游戏中显示踏板和换挡悬浮窗",
                    checked = showOverlay,
                    onCheckedChange = onShowOverlay
                )
                SwitchRow(
                    title = "关闭自动换挡",
                    summary = "禁用车载自动换挡逻辑",
                    checked = disableAutoGear,
                    onCheckedChange = onDisableAutoGear
                )
            }
        }

        SmallTitle(text = "踏板映射")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SliderRow(
                    title = "死区",
                    summary = "踏板中间过渡区域的无效范围",
                    value = deadzone,
                    onValueChange = onDeadzone,
                    valueRange = 0f..0.2f,
                    displayFormat = { String.format("%.0f%%", it * 100) }
                )
                SliderRow(
                    title = "过渡点",
                    summary = "油门和刹车的分界位置",
                    value = transition,
                    onValueChange = onTransition,
                    valueRange = 0.2f..0.8f,
                    displayFormat = { String.format("%.0f%%", it * 100) }
                )
                CurveDropdown(
                    curve = curve,
                    onCurve = onCurve
                )
            }
        }
    }
}

@Composable
private fun CurveDropdown(
    curve: PedalCurve,
    onCurve: (PedalCurve) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "响应曲线", fontSize = 15.sp)
        Text(text = "选择油门/刹车的响应方式", fontSize = 13.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        CurveSelector(curve = curve, onCurve = onCurve)
    }
}

@Composable
private fun CurveSelector(curve: PedalCurve, onCurve: (PedalCurve) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = curveName(curve), fontSize = 15.sp)
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.Gray
            )
        }
    }

    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        PedalCurve.entries.forEach { item ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(curveName(item)) },
                onClick = {
                    onCurve(item)
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun SettingsPage(
    logEnabled: Boolean,
    onLogEnabled: (Boolean) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SmallTitle(text = "日志")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SwitchRow(
                    title = "启用日志",
                    summary = "记录模块运行日志以便排查问题",
                    checked = logEnabled,
                    onCheckedChange = onLogEnabled
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { exportAndShareLog(context) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "导出并分享日志", fontSize = 15.sp)
                    Text(text = "导出当前日志文件并分享", fontSize = 13.sp, color = Color.Gray)
                }
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp)
            Text(text = summary, fontSize = 13.sp, color = Color.Gray)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    summary: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayFormat: (Float) -> String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "$title: ${displayFormat(value)}", fontSize = 15.sp)
        Text(text = summary, fontSize = 13.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

private fun curveName(curve: PedalCurve): String = when (curve) {
    PedalCurve.LINEAR -> "线性"
    PedalCurve.QUADRATIC -> "二次"
    PedalCurve.EXPONENTIAL -> "指数"
}

private fun exportAndShareLog(context: Context) {
    Toast.makeText(context, "日志导出功能即将上线", Toast.LENGTH_SHORT).show()
}
