package tools.alamobile.mod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tools.alamobile.mod.config.ModConfig
import tools.alamobile.mod.config.ModConfig.PedalCurve
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
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
                ConfigScreen()
            }
        }
    }
}

@Composable
private fun ConfigScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { ModConfig.read(context) }

    var enableControlReplacement by remember {
        mutableStateOf(settings.enableControlReplacement)
    }
    var enableAutoDrs by remember { mutableStateOf(settings.enableAutoDrs) }
    var showOverlay by remember { mutableStateOf(settings.showOverlay) }
    var deadzone by remember { mutableFloatStateOf(settings.pedalDeadzone) }
    var transition by remember { mutableFloatStateOf(settings.pedalTransition) }
    var curve by remember { mutableStateOf(settings.pedalCurve) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = "Ala Mobile Tool") }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SmallTitle(text = "功能开关")

            SwitchRow(
                title = "踏板覆盖",
                checked = enableControlReplacement,
                onCheckedChange = { enableControlReplacement = it }
            )

            SwitchRow(
                title = "自动 DRS",
                checked = enableAutoDrs,
                onCheckedChange = { enableAutoDrs = it }
            )

            SwitchRow(
                title = "显示悬浮窗",
                checked = showOverlay,
                onCheckedChange = { showOverlay = it }
            )

            SmallTitle(
                text = "踏板映射",
                modifier = Modifier.padding(top = 16.dp)
            )

            Text(
                text = "死区: ${String.format("%.0f%%", deadzone * 100)}",
                modifier = Modifier.padding(top = 8.dp)
            )
            SliderRow(
                value = deadzone,
                onValueChange = { deadzone = it },
                valueRange = 0f..0.2f
            )

            Text(
                text = "过渡点: ${String.format("%.0f%%", transition * 100)}",
                modifier = Modifier.padding(top = 8.dp)
            )
            SliderRow(
                value = transition,
                onValueChange = { transition = it },
                valueRange = 0.2f..0.8f
            )

            Text(
                text = "曲线: ${curve.name}",
                modifier = Modifier.padding(top = 8.dp)
            )
            SegmentedButtonRow(
                items = PedalCurve.entries.map { it.name },
                selectedIndex = PedalCurve.entries.indexOf(curve),
                onSelected = { curve = PedalCurve.entries[it] }
            )

            Button(
                onClick = {
                    ModConfig.write(
                        context,
                        ModConfig.Settings(
                            enableControlReplacement = enableControlReplacement,
                            enableAutoDrs = enableAutoDrs,
                            showOverlay = showOverlay,
                            pedalDeadzone = deadzone,
                            pedalTransition = transition,
                            pedalCurve = curve
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    // miuix-ui may or may not expose a Switch component in this version;
    // use a simple button that toggles its state for portability.
    Button(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(text = "$title: ${if (checked) "开" else "关"}")
    }
}

@Composable
private fun SliderRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    // Fallback numeric display; miuix-ui in this version does not expose
    // a stable Slider in the public API, so we use +/- stepper buttons.
    val step = (valueRange.endInclusive - valueRange.start) / 20f

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = String.format("%.3f", value))
        Button(
            onClick = { onValueChange((value - step).coerceIn(valueRange)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("-")
        }
        Button(
            onClick = { onValueChange((value + step).coerceIn(valueRange)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("+")
        }
    }
}

@Composable
private fun SegmentedButtonRow(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, label ->
            Button(
                onClick = { onSelected(index) },
                modifier = Modifier.fillMaxWidth()
            ) {
                val marker = if (index == selectedIndex) "● " else "○ "
                Text(text = marker + label)
            }
        }
    }
}
