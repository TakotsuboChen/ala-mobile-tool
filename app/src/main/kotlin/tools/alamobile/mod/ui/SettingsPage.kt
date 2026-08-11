package tools.alamobile.mod.ui

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.alamobile.mod.EulaManager
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SettingsPage(
    uiState: ConfigUiState,
    onSave: () -> Unit,
    bottomBarHeight: Dp = 0.dp
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    // 「用户协议」点击后清除同意状态并当场弹协议。
    // 弹窗渲染在 SettingsPage 自己的 miuix Scaffold 里（popupHost 提供 OverlayDialog 宿主），
    // 不同意/按返回 → finish 退出 Activity（与首次启动协议弹窗行为一致）。
    var showEulaReconfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "设置",
                    color = barColor,
                    scrollBehavior = scrollBehavior
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmallTitle(
                        text = "日志",
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchRow(
                            title = "启用日志",
                            summary = "记录模块运行日志以便排查问题",
                            icon = Icons.Rounded.Warning,
                            checked = uiState.logEnabled.value,
                            onCheckedChange = {
                                uiState.logEnabled.value = it
                                onSave()
                            }
                        )
                        ArrowRow(
                            title = "导出并分享日志",
                            summary = "导出当前日志文件并分享",
                            icon = Icons.Rounded.Share,
                            onClick = {
                                Toast.makeText(context, "日志导出功能即将上线", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    SmallTitle(
                        text = "调试",
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowRow(
                            title = "清除激活标记",
                            summary = "删除 LSPosed / Non-root 激活状态缓存",
                            icon = Icons.Rounded.Delete,
                            onClick = {
                                tools.alamobile.mod.LsposedStatus.clearAll(context)
                                Toast.makeText(context, "已清除激活标记", Toast.LENGTH_SHORT).show()
                            }
                        )
                        ArrowRow(
                            title = "用户协议",
                            summary = "重新查看并确认用户协议",
                            icon = Icons.AutoMirrored.Rounded.Article,
                            onClick = {
                                EulaManager.clear(context)
                                showEulaReconfirm = true
                            }
                        )
                        ArrowRow(
                            title = "关于",
                            summary = "Ala Mobile Tool 模块信息",
                            icon = Icons.Rounded.Info,
                            onClick = {
                                Toast.makeText(
                                    context,
                                    "Ala Mobile Tool v${tools.alamobile.mod.BuildConfig.VERSION_NAME}",
                                    Toast.LENGTH_SHORT
                                ).show()
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
            onAccept = {
                EulaManager.accept(context)
                showEulaReconfirm = false
            },
            onExit = {
                showEulaReconfirm = false
                (context as? ComponentActivity)?.finish()
            }
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
                tint = MiuixTheme.colorScheme.onBackground
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 15.sp)
                Text(text = summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ArrowRow(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    // 整行可点击，左图标已传达语义，右侧不加装饰图标。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp),
                tint = MiuixTheme.colorScheme.onBackground
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 15.sp)
                Text(text = summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
