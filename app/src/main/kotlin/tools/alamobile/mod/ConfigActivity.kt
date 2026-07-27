package tools.alamobile.mod

import android.os.Bundle
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.config.ModConfig.PedalCurve
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
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

@Composable
private fun ConfigScreen(onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { ModConfig.read(context) }

    var enableControlReplacement by remember {
        mutableStateOf(settings.enableControlReplacement)
    }
    var enableAutoDrs by remember { mutableStateOf(settings.enableAutoDrs) }
    var showOverlay by remember { mutableStateOf(settings.showOverlay) }
    var disableAutoGear by remember { mutableStateOf(settings.disableAutoGear) }
    var deadzone by remember { mutableFloatStateOf(settings.pedalDeadzone) }
    var transition by remember { mutableFloatStateOf(settings.pedalTransition) }
    var curve by remember { mutableStateOf(settings.pedalCurve) }

    val onSave: () -> Unit = {
        ModConfig.write(
            context,
            ModConfig.Settings(
                enableControlReplacement = enableControlReplacement,
                enableAutoDrs = enableAutoDrs,
                showOverlay = showOverlay,
                disableAutoGear = disableAutoGear,
                pedalDeadzone = deadzone,
                pedalTransition = transition,
                pedalCurve = curve
            )
        )
        onFinish()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = "Ala Mobile Tool",
                actions = {
                    TextButton(
                        text = "保存",
                        onClick = onSave
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SmallTitle(text = "功能开关")

            ToggleRow(
                title = "踏板覆盖",
                checked = enableControlReplacement,
                onCheckedChange = { enableControlReplacement = it }
            )

            ToggleRow(
                title = "自动 DRS",
                checked = enableAutoDrs,
                onCheckedChange = { enableAutoDrs = it }
            )

            ToggleRow(
                title = "显示悬浮窗",
                checked = showOverlay,
                onCheckedChange = { showOverlay = it }
            )

            ToggleRow(
                title = "关闭自动换挡",
                checked = disableAutoGear,
                onCheckedChange = { disableAutoGear = it }
            )

            SmallTitle(
                text = "踏板映射",
                modifier = Modifier.padding(top = 24.dp)
            )

            SliderRow(
                title = "死区",
                value = deadzone,
                onValueChange = { deadzone = it },
                valueRange = 0f..0.2f,
                displayFormat = { String.format("%.0f%%", it * 100) }
            )

            SliderRow(
                title = "过渡点",
                value = transition,
                onValueChange = { transition = it },
                valueRange = 0.2f..0.8f,
                displayFormat = { String.format("%.0f%%", it * 100) }
            )

            Text(
                text = "响应曲线",
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            TabRow(
                tabs = PedalCurve.entries.map { curveName(it) },
                selectedTabIndex = PedalCurve.entries.indexOf(curve),
                onTabSelected = { index ->
                    curve = PedalCurve.entries[index]
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存并退出")
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
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
        Text(text = title)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayFormat: (Float) -> String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$title: ${displayFormat(value)}",
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
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
