package tools.alamobile.mod.ui.screen.paddock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tools.alamobile.mod.PaddockClient
import tools.alamobile.mod.ui.navigation3.LocalNavigator
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
 * 计时赛排行榜二级页（围场 → 计时赛排行榜 push 进来）。
 * 顶部 tab 切换：积分总榜 / 赛道榜（赛道选择 16 条 + 版本筛选）。
 * 榜单行：排名 圆形头像 用户名 ……… 右对齐圈速/积分（一人一行连排，无分隔）；
 * 内边距对齐 miuix 标准 16dp（与 preference 行左右端一致）。
 *
 * 性能（2026-09-02 定案）：榜单行直接是外层 LazyColumn 的 items——
 * 数据到达帧只组合可见行，几百行不会一次性组合撞上转场/淡入动画导致掉帧。
 * 连体卡视觉由"每行同底色 + 首末行圆角"拼出（Card 源码：surfaceContainer + 16dp 圆角）。
 * 数据替换时整体 alpha 淡入（graphicsLayer 渲染层，不触发重组开销）。
 */
@Composable
fun LeaderboardScreen() {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    // 0=积分榜 1=赛道榜
    var tabIndex by remember { mutableIntStateOf(0) }
    // 赛道榜参数（gp_index 0..15，与 TRACK_NAMES 索引一致；默认第一个=阿尔伯特公园）
    var selectedTrack by remember { mutableIntStateOf(0) }
    // 0=所有版本 1=8.0.6(当前) 2=8.0.4(历史)
    var selectedVersion by remember { mutableIntStateOf(0) }
    // 纯版本码映射表（下标 = spinner 序号-1）；哨兵"所有版本"由 selectedVersion==0 判断，
    // 勿把 0 混进数组——曾致选 8.0.6 发 version=0（服务端空榜）选 8.0.4 发 8.0.6 的错位 bug
    val VERSION_CODES = arrayOf(tools.alamobile.mod.offsets.OffsetTable.PADDOCK_VERSION_CODE, 200146)

    var points by remember { mutableStateOf<List<PaddockClient.PointsEntry>>(emptyList()) }
    var trackBoard by remember { mutableStateOf<PaddockClient.TrackBoard?>(null) }
    // 首次进入（无旧内容可保留）才显示加载中；之后切条件保留旧内容
    var everLoaded by remember { mutableStateOf(false) }
    // 页面进入转场（miuix NavDriver 程序化转场 500ms tween）结束后才渲染榜单行：
    // 转场中途插行淡入会打断转场动画观感；数据请求与转场并行，转场一结束即显示。
    var enterSettled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(NAV_ENTER_SETTLE_MILLIS)
        enterSettled = true
    }
    // 两阶段切换（榜单内切类型/赛道/版本）：切换发生 → 旧行**立即**显式渐隐（与筛选卡
    // 伸缩动画并行，不排队）→ 淡出完成后换 visibleBoard 数据源 → 新行经 animateItem 淡入。
    // 关键：旧行渐隐必须用显式 alpha（而非 animateItem 的移除淡出）——移除发生在换源帧，
    // 那一刻筛选卡伸缩刚结束，移除+插入同帧竞争布局就是"闪一下"的来源。
    var visibleBoard by remember { mutableStateOf(0 to 0) }  // (tabIndex, track) 实际显示的榜单
    var switchSeq by remember { mutableIntStateOf(0) }       // 切换序号，驱动淡出→换数据协程
    val boardAlpha = remember { Animatable(1f) }

    LaunchedEffect(switchSeq) {
        if (switchSeq == 0) return@LaunchedEffect
        boardAlpha.animateTo(0f, tween(BOARD_FADE_OUT_MILLIS.toInt()))
        visibleBoard = tabIndex to selectedTrack
        boardAlpha.snapTo(1f)  // 新行由 animateItem fadeIn 从 0 起；显式 alpha 复位为 1
    }

    LaunchedEffect(tabIndex, selectedTrack, selectedVersion) {
        val v = if (selectedVersion == 0) null else VERSION_CODES.getOrNull(selectedVersion - 1)
        if (tabIndex == 0) {
            points = withContext(Dispatchers.IO) { PaddockClient.fetchPointsBoard(v) }
        } else {
            trackBoard = withContext(Dispatchers.IO) { PaddockClient.fetchTrackBoard(selectedTrack, v) }
        }
        everLoaded = true
        // 无在途切换（首次加载）→ 直接显示；有在途切换 → 等淡出协程换源
        if (switchSeq == 0) {
            visibleBoard = tabIndex to selectedTrack
            boardAlpha.snapTo(1f)
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "计时赛排行榜",
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            modifier = Modifier
                                .padding(12.dp)
                                .clickable { navigator.pop() },
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
            // 榜单行直接作为外层 LazyColumn 的 items：数据到达帧只组合可见行，
            // 不会像"Card + 全量 forEach"那样一次性组合几百行导致掉帧。
            // 连体卡视觉 = 每行自带 surfaceContainer 背景 + 首末行圆角拼接。
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
                item(key = "filter") {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // ── 页签 + 筛选（onSelect 先启动两阶段时序：旧行淡出→换源→新行淡入）──
                        Card(modifier = Modifier.fillMaxWidth()) {
                            TabRowLite(tabIndex, onTab = {
                                if (it != tabIndex) {
                                    tabIndex = it
                                    switchSeq++
                                }
                            })
                            if (tabIndex == 1) {
                                TrackSpinner(selectedTrack, onPick = {
                                    if (it != selectedTrack) {
                                        selectedTrack = it
                                        switchSeq++
                                    }
                                })
                            }
                            VersionSpinner(selectedVersion) {
                                if (it != selectedVersion) {
                                    selectedVersion = it
                                    switchSeq++
                                }
                            }
                        }
                    }
                }

                // ── 榜单行（连排行，无分隔）──
                // 渲染数据 = visibleBoard（淡出完成后才切换），不是当前筛选条件——
                // 这保证"旧内容淡出完成 → 新内容才淡入"的两阶段观感。
                // 行出现/消失用 Modifier.animateItem()（框架在首帧前定初始 alpha，无抢先帧闪现）。
                val (visTab, visTrack) = visibleBoard
                val showRows = everLoaded && enterSettled
                if (!showRows) {
                    item(key = "loading") {
                        Text(
                            "加载中…",
                            fontSize = 14.sp,
                            color = colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else if (visTab == 0) {
                    val entries = points
                    itemsIndexed(entries, key = { i, e -> "p#$i${e.username}" }) { i, e ->
                        BoardRow(
                            rank = i + 1,
                            avatarUrl = e.avatarUrl,
                            name = e.username,
                            value = "${e.points} 分",
                            rowShape = boardRowShape(i, entries.size),
                            alpha = boardAlpha.value,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(250),
                                fadeOutSpec = tween(150),
                            ),
                        )
                    }
                    if (entries.isEmpty()) {
                        item(key = "empty") { BoardEmptyRow(boardAlpha.value) }
                    }
                    item(key = "footer-space") { Spacer(Modifier.height(12.dp)) }
                } else {
                    val entries = trackBoard?.entries.orEmpty()
                    itemsIndexed(entries, key = { i, e -> "t#$i${e.username}" }) { i, e ->
                        BoardRow(
                            rank = e.rank,
                            avatarUrl = e.avatarUrl,
                            name = e.username,
                            value = e.lapDisplay,
                            rowShape = boardRowShape(i, entries.size),
                            alpha = boardAlpha.value,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(250),
                                fadeOutSpec = tween(150),
                            ),
                        )
                    }
                    if (entries.isEmpty()) {
                        item(key = "empty") { BoardEmptyRow(boardAlpha.value) }
                    }
                    item(key = "footer-space") { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

/** 榜单行圆角：首行上圆角、末行下圆角、中间直角，拼接成连体卡（miuix Card = 16dp 圆角）。 */
private fun boardRowShape(index: Int, size: Int): Shape = when {
    size == 1 -> RoundedCornerShape(16.dp)
    index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    index == size - 1 -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
    else -> RectangleShape
}

/** 页面进入转场时长（miuix NavDriverSpec.PROGRAMMATIC_DURATION_MILLIS，契约值勿随意改）。 */
private const val NAV_ENTER_SETTLE_MILLIS = 500L

/** 榜单切换旧行淡出时长：与 animateItem fadeOutSpec 一致，保证淡出播完才换源。 */
private const val BOARD_FADE_OUT_MILLIS = 150L

/** 头像内存缓存（屏幕级即可：榜单页进出重建，天然淘汰）。key = avatar_url。 */
private val avatarCache = ConcurrentHashMap<String, Bitmap>()

/** 头像显示尺寸 36dp → 像素约 72~108px；解码降采样到 2 的幂采样率，避免 512px 全尺寸位图 ×N 张的内存/GC 压力。 */
private fun decodeAvatarScaled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 144) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

/** 榜单行头像：有 URL 异步取（缓存），无 URL/失败显示 Person 占位。 */
@Composable
private fun AvatarOrPlaceholder(avatarUrl: String?) {
    var bmp by remember(avatarUrl) { mutableStateOf(avatarUrl?.let { avatarCache[it] }) }
    LaunchedEffect(avatarUrl) {
        if (avatarUrl != null && bmp == null) {
            val b = withContext(Dispatchers.IO) {
                PaddockClient.fetchAvatar(avatarUrl)?.let { decodeAvatarScaled(it) }
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

/** 前三名奖牌 emoji（其余名次显示数字）。 */
private fun rankLabel(rank: Int): String = when (rank) {
    1 -> "🥇"
    2 -> "🥈"
    3 -> "🥉"
    else -> "$rank"
}

/** 空榜单行（与数据行同样的底色/圆角，保持连体卡观感）。 */
@Composable
private fun BoardEmptyRow(alpha: Float = 1f) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .squircleRowBackground(RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "暂无成绩",
            fontSize = 14.sp,
            color = colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/**
 * 一行：排名（28dp 等宽槽位居中） 圆形头像 用户名 …… 右对齐数值。行间无分隔，连排。
 * 自带 surfaceContainer 底色 + 拼接圆角（rowShape）代替外层 Card；
 * alpha 为显式渐隐通道（切换时旧行渐隐，与新行 animateItem 淡入分工，见 switchSeq 注释）；
 * 水平内边距 16dp = miuix preference 标准（BasicComponentDefaults.InsideMargin），
 * 与页面上其他 preference 卡的左右端严格对齐。
 */
@Composable
private fun BoardRow(
    rank: Int,
    avatarUrl: String?,
    name: String,
    value: String,
    rowShape: Shape,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .squircleRowBackground(rowShape)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = rankLabel(rank),
            fontSize = 15.sp,
            color = colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
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

/** 行底色 = miuix Card 默认 surfaceContainer（CardDefaults.defaultColors 的取值）。 */
@Composable
private fun Modifier.squircleRowBackground(shape: Shape): Modifier {
    // miuix Card 内部用 squircleSurface；行级拼接用 background + 同款圆角（视觉一致，行级拆分无 squircle 依赖）。
    return this.background(color = colorScheme.surfaceContainer, shape = shape)
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
    val items = remember { listOf(DropdownItem(text = "全部"), DropdownItem(text = "8.0.6"), DropdownItem(text = "8.0.4")) }
    OverlaySpinnerPreference(
        items = items,
        selectedIndex = selected,
        title = "版本",
        onSelectedIndexChange = onPick,
    )
}