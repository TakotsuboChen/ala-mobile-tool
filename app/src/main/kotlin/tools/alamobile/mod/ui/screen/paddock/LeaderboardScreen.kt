package tools.alamobile.mod.ui.screen.paddock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.alamobile.mod.PaddockClient
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 圈速排行榜二级页（围场 → 圈速排行榜 push 进来）。
 * 顶部 tab 切换：积分总榜 / 赛道榜（赛道选择 16 条 + 版本筛选）。
 * 数据只读缓存 + 手动下拉刷新语义简化为：页签/赛道/版本切换即重取（LaunchedEffect key）。
 */
@Composable
fun LeaderboardScreen() {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    // 0=积分榜 1=赛道榜
    var tabIndex by remember { mutableIntStateOf(0) }
    // 赛道榜参数
    var selectedTrack by remember { mutableIntStateOf(12) }   // 默认蒙扎
    var selectedVersion by remember { mutableIntStateOf(0) }  // 0=总榜 1=8.0.4(200146)
    val VERSION_CODES = arrayOf(200146)
    val VERSION_LABELS = remember { listOf("总榜", "8.0.4") }

    var points by remember { mutableStateOf<List<PaddockClient.PointsEntry>>(emptyList()) }
    var trackBoard by remember { mutableStateOf<PaddockClient.TrackBoard?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(tabIndex, selectedTrack, selectedVersion) {
        loading = true
        val v = if (selectedVersion == 0) null else VERSION_CODES.getOrNull(selectedVersion - 1)
        if (tabIndex == 0) {
            points = withContext(Dispatchers.IO) { PaddockClient.fetchPointsBoard(v) }
        } else {
            trackBoard = withContext(Dispatchers.IO) { PaddockClient.fetchTrackBoard(selectedTrack, v) }
        }
        loading = false
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "圈速排行榜",
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            modifier = Modifier.padding(12.dp),
                            contentDescription = "返回",
                        )
                    },
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
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                    ) {
                        // ── 页签 + 筛选 ──
                        Card(modifier = Modifier.fillMaxWidth()) {
                            TabRowLite(tabIndex, onTab = { tabIndex = it })
                            if (tabIndex == 1) {
                                TrackSpinner(selectedTrack, onPick = { selectedTrack = it })
                            }
                            VersionSpinner(selectedVersion) { selectedVersion = it }
                        }

                        // ── 榜单内容 ──
                        if (loading) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                top.yukonga.miuix.kmp.basic.Text("加载中…", modifier = Modifier.padding(16.dp), fontSize = 14.sp)
                            }
                        } else if (tabIndex == 0) {
                            PointsBoard(points)
                        } else {
                            TrackBoardView(trackBoard)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabRowLite(tab: Int, onTab: (Int) -> Unit) {
    val items = remember { listOf(DropdownItem(text = "积分榜"), DropdownItem(text = "赛道榜")) }
    OverlaySpinnerPreference(
        items = items,
        selectedIndex = tab,
        title = "榜单类型",
        onSelectedIndexChange = onTab,
    )
}

@Composable
private fun TrackSpinner(selected: Int, onPick: (Int) -> Unit) {
    // 16 赛道中文名（契约：模块侧与服务端 track_display_name 一致，见 PADDOCK_PLAN §5）
    val names = remember {
        listOf(
            "🇦🇺 阿尔伯特公园", "🇨🇳 上海国际赛车场", "🇧🇭 巴林国际赛车场", "🇮🇹 伊莫拉",
            "🇪🇸 加泰罗尼亚", "🇲🇨 摩纳哥", "🇨🇦 吉尔·维伦纽夫", "🇦🇹 红牛环",
            "🇬🇧 银石", "🇩🇪 霍根海姆", "🇭🇺 亨格罗宁", "🇧🇪 斯帕",
            "🇮🇹 蒙扎", "🇯🇵 铃鹿", "🇧🇷 英特拉格斯", "🇦🇪 亚斯码头",
        )
    }
    val items = remember(names) { names.map { DropdownItem(text = it) } }
    OverlaySpinnerPreference(
        items = items,
        selectedIndex = selected,
        title = "赛道",
        onSelectedIndexChange = onPick,
    )
}

@Composable
private fun VersionSpinner(selected: Int, onPick: (Int) -> Unit) {
    val items = remember { listOf(DropdownItem(text = "总榜"), DropdownItem(text = "8.0.4 版本榜")) }
    OverlaySpinnerPreference(
        items = items,
        selectedIndex = selected,
        title = "版本",
        onSelectedIndexChange = onPick,
    )
}

@Composable
private fun PointsBoard(points: List<PaddockClient.PointsEntry>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            top.yukonga.miuix.kmp.basic.Text("总积分（16 赛道满分 1600）", fontSize = 13.sp)
            if (points.isEmpty()) {
                top.yukonga.miuix.kmp.basic.Text("暂无成绩", fontSize = 14.sp, color = colorScheme.onBackground.copy(alpha = 0.5f))
            }
            points.forEachIndexed { i, e ->
                top.yukonga.miuix.kmp.basic.Text(
                    "${i + 1}.  ${e.username} — ${e.points} 分",
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun TrackBoardView(board: PaddockClient.TrackBoard?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            top.yukonga.miuix.kmp.basic.Text(board?.trackName ?: "", fontSize = 16.sp)
            val entries = board?.entries.orEmpty()
            if (entries.isEmpty()) {
                top.yukonga.miuix.kmp.basic.Text("暂无成绩", fontSize = 14.sp, color = colorScheme.onBackground.copy(alpha = 0.5f))
            }
            entries.forEach { e ->
                top.yukonga.miuix.kmp.basic.Text(
                    "#${e.rank}  ${e.username} — ${e.lapDisplay}",
                    fontSize = 15.sp,
                )
            }
        }
    }
}