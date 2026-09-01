package tools.alamobile.mod.ui.screen.paddock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tools.alamobile.mod.PaddockClient
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.util.concurrent.ConcurrentHashMap

/**
 * 圈速排行榜二级页（围场 → 圈速排行榜 push 进来）。
 * 顶部 tab 切换：积分总榜 / 赛道榜（赛道选择 16 条 + 版本筛选）。
 * 榜单行：排名 圆形头像 用户名 ……… 右对齐圈速/积分（一人一行连排，无分隔）。
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
    // 赛道榜参数（gp_index 2..17 → 赛道表索引 0..15，显示名见 TRACK_NAMES）
    var selectedTrack by remember { mutableIntStateOf(12) }   // 默认蒙扎（gp_index=12）
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // ── 页签 + 筛选 ──
                        Card(modifier = Modifier.fillMaxWidth()) {
                            TabRowLite(tabIndex, onTab = { tabIndex = it })
                            if (tabIndex == 1) {
                                TrackSpinner(selectedTrack, onPick = { selectedTrack = it })
                            }
                            VersionSpinner(selectedVersion) { selectedVersion = it }
                        }

                        // ── 榜单内容（连排行，无分隔）──
                        if (loading) {
                            Text(
                                "加载中…",
                                fontSize = 14.sp,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(4.dp),
                            )
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

/** 头像内存缓存（屏幕级即可：榜单页进出重建，天然淘汰）。key = avatar_url。 */
private val avatarCache = ConcurrentHashMap<String, Bitmap>()

/** 榜单行头像：有 URL 异步取（缓存），无 URL/失败显示 Person 占位。 */
@Composable
private fun AvatarOrPlaceholder(avatarUrl: String?) {
    var bmp by remember(avatarUrl) { mutableStateOf(avatarUrl?.let { avatarCache[it] }) }
    LaunchedEffect(avatarUrl) {
        if (avatarUrl != null && bmp == null) {
            val b = withContext(Dispatchers.IO) {
                PaddockClient.fetchAvatar(avatarUrl)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            if (b != null) {
                avatarCache[avatarUrl] = b
                bmp = b
            }
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Person,
                modifier = Modifier.size(22.dp),
                contentDescription = null,
                tint = colorScheme.onBackground.copy(alpha = 0.4f),
            )
        }
    }
}

/** 积分榜行：排名 头像 用户名 …… 右对齐积分。 */
@Composable
private fun PointsBoard(points: List<PaddockClient.PointsEntry>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            points.forEachIndexed { i, e ->
                BoardRow(
                    rank = i + 1,
                    avatarUrl = e.avatarUrl,
                    name = e.username,
                    value = "${e.points} 分",
                )
            }
            if (points.isEmpty()) {
                Text("暂无成绩", fontSize = 14.sp, color = colorScheme.onBackground.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

/** 赛道榜行。 */
@Composable
private fun TrackBoardView(board: PaddockClient.TrackBoard?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            board?.entries.orEmpty().forEach { e ->
                BoardRow(
                    rank = e.rank,
                    avatarUrl = e.avatarUrl,
                    name = e.username,
                    value = e.lapDisplay,
                )
            }
            if (board?.entries.isNullOrEmpty()) {
                Text("暂无成绩", fontSize = 14.sp, color = colorScheme.onBackground.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

/** 一行：排名（等宽） 圆形头像 用户名 …… 右对齐数值。行间无分隔，连排。 */
@Composable
private fun BoardRow(rank: Int, avatarUrl: String?, name: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "$rank",
            fontSize = 15.sp,
            color = colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.width(24.dp),
        )
        AvatarOrPlaceholder(avatarUrl)
        Text(
            text = name,
            fontSize = 15.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 15.sp,
            color = colorScheme.onBackground,
        )
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

/** 16 赛道中文名（契约：PADDOCK_PLAN §5 全称，与服务端 track_display_name 一致）。 */
private val TRACK_NAMES = listOf(
    "🇦🇺 阿尔伯特公园赛道", "🇨🇳 上海国际赛车场", "🇧🇭 巴林国际赛车场", "🇮🇹 伊莫拉赛道",
    "🇪🇸 加泰罗尼亚赛道", "🇲🇨 摩纳哥赛道", "🇨🇦 吉尔·维伦纽夫赛道", "🇦🇹 红牛环赛道",
    "🇬🇧 银石赛道", "🇩🇪 霍根海姆赛道", "🇭🇺 亨格罗宁赛道", "🇧🇪 斯帕-弗朗科尔尚赛道",
    "🇮🇹 蒙扎国家赛车场", "🇯🇵 铃鹿赛道", "🇧🇷 英特拉格斯赛道", "🇦🇪 亚斯码头赛道",
)

@Composable
private fun TrackSpinner(selected: Int, onPick: (Int) -> Unit) {
    val items = remember { TRACK_NAMES.map { DropdownItem(text = it) } }
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