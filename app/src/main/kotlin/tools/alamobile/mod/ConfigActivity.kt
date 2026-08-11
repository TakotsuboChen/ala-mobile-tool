package tools.alamobile.mod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import tools.alamobile.mod.ui.ConfigMainScreen
import tools.alamobile.mod.ui.LocalEnableBlur
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
                val dispatcherOwner = rememberNavigationEventDispatcherOwner(parent = null)
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides dispatcherOwner,
                    LocalEnableBlur provides true
                ) {
                    ConfigMainScreen(onFinish = { finish() })
                }
            }
        }
    }
}
