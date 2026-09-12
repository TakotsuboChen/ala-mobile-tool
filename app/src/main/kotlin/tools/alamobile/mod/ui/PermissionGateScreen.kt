package tools.alamobile.mod.ui

import android.app.Activity
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.util.AllFilesPermission

/**
 * 「必要权限」满屏不可跳过门（2026-09-12，UI 于同日对齐模块主界面）。
 *
 * 未授予「所有文件访问」（AFA）时占据整个主界面，用户无法进入模块任何页面。
 * 这是硬性前置：无 AFA 时模块**无法**把配置/登录态可靠传给游戏（跨包 media 文件
 * 通道是唯一在三重约束下可用的通道，见 [tools.alamobile.mod.config.CrossPkgMailbox]）。
 *
 * 布局与所有主页面（概览/配置/设置/围场）共用同一套外壳——`Scaffold + BlurredBar +
 * TopAppBar + LazyColumn(overScrollVertical + scrollEndHaptic) + layerBackdrop`：
 * - 毛玻璃 TopAppBar「必要权限」（blur 开关跟随设置，和其它页一致）
 * - 12dp 页边距 + 12dp 纵向 Card 间距（与 OverviewPagerMiuix 完全一致）
 * - 权限卡：文件夹 Icon + 标题 + 描述 + 右侧状态值（文案仍由用户定案）
 * - 底部两个全宽按钮：上=蓝「去授予权限」（主操作），下=灰「退出模块」
 *
 * 交互：用户开完系统开关返回模块 → [ConfigActivity] 的 ON_RESUME 重查 →
 * 已授权则门消失，自动进主界面。
 */
@Composable
fun PermissionGateScreen() {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "必要权限",
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
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
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "授予所有文件访问权限",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "若不授权则模块将无法运行",
                                        fontSize = 14.sp,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "未授权",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFE53935),
                                )
                            }
                        }

                        TextButton(
                            text = "去授予权限",
                            onClick = { AllFilesPermission.openSettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                        TextButton(
                            text = "退出模块",
                            onClick = { (context as? Activity)?.finish() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
