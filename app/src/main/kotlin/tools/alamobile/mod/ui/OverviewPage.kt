package tools.alamobile.mod.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun OverviewPage() {
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
            contentPadding = innerPadding,
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
    val activated = remember { LsposedStatus.isActivated(context) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (activated) {
                Color(0xFFDFFAE4)
            } else {
                MiuixTheme.colorScheme.surface
            }
        )
    ) {
        BasicComponent(
            title = if (activated) "已激活" else "未激活",
            summary = if (activated) {
                "模块已通过 LSPosed 加载"
            } else {
                "请前往 LSPosed Manager 启用本模块"
            },
            startAction = {
                Icon(
                    imageVector = if (activated) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(28.dp),
                    tint = if (activated) Color(0xFF36D167) else Color(0xFFFF5252)
                )
            }
        )
    }
}

@Composable
private fun DeviceInfoCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            BasicComponent(
                title = "检查更新",
                summary = "点击检查 GitHub Releases",
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
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.Phone,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                },
                onClick = { openExternalUrl(context, "https://qun.qq.com/universal-share/share?ac=1&authKey=V0nuKHg0u%2BZKVi%2FjgDReAiZSCQdbMb0yMwaOSV49gejQWRtdz%2BG4G6eQQgWyFOJB&busi_data=eyJncm91cENvZGUiOiI3NTc5NDA3MDgiLCJ0b2tlbiI6IjVzRjZTTWpLckJIRExvRTk3K0QzVzJGK2N4QURRM2RwRjJWNkw0L29wcG9wa3IyNTV6OU9YS2dhSVZGV2ZIeTFQaiIsInVpbiI6IjEyNTk5NzY1NTUyMCJ9%3D&data=x1JvsLJUAovAdpfNmLQpuTN_-yGbUrMfCJ1VSQqD-QbIzj9-ZLiRKNEHNbJXpokkPhx5cc-RG47HyWYUrPBtTA&svctype=4&tempid=h5_group_info") }
            )
            BasicComponent(
                title = "GitHub 源代码",
                summary = "欢迎 Star",
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
