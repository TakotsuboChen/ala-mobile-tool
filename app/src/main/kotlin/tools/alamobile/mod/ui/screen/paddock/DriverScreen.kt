package tools.alamobile.mod.ui.screen.paddock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 车手主页（2026-10-18）：资料卡 + 全量圈速记录（分页 + 四维筛选）。
 *
 * ## 入口
 * - 围场主页信息卡（整卡可点，右侧指引箭头）
 * - 计时赛排行榜任意一行
 *
 * ## 分页（用户定案：一次最多 30 条）
 * 滚到接近底部时自动拉下一页；`total` 来自服务端，`entries.size >= total` 即到底。
 *
 * ⚠️ **筛选变更必须作废在途请求**：用 `filterEpoch` 递增做代次标记。否则快速
 *    切换筛选时，旧条件的第 2 页会追加到新条件的第 1 页后面（列表混入不匹配行）。
 *
 * ⚠️ **分页游标用 `entries.size`**，不用页码：服务端按 `created_at DESC, lap_id DESC`
 *    稳定排序，条数即偏移量；用页码在"筛选后总数不足整页"时会算错。
 */
@Composable
fun DriverScreen(userId: String) {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    // ── 筛选状态（0 = 全部）──
    var selectedTrack by remember { mutableIntStateOf(0) }   // 0=全部，1..16 → 赛道 0..15
    var selectedVersion by remember { mutableIntStateOf(0) } // 0=全部，1..2 → VERSION_CODES
    var zeroOnly by remember { mutableStateOf(false) }
    var bestOnly by remember { mutableStateOf(false) }

    val VERSION_CODES = arrayOf(tools.alamobile.mod.offsets.OffsetTable.PADDOCK_VERSION_CODE, 200146)

    var profile by remember { mutableStateOf<PaddockClient.DriverProfile?>(null) }
    var entries by remember { mutableStateOf<List<PaddockClient.DriverLap>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var everLoaded by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    // 筛选代次：每次筛选变化 +1；在途请求回来时代次不符即丢弃。
    var filterEpoch by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()

    // 页面进入转场（miuix NavDriver 程序化转场 500ms）结束后才渲染行，同排行榜页。
    var enterSettled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(NAV_ENTER_SETTLE_MILLIS)
        enterSettled = true
    }

    // 首屏 + 筛选变更：重置列表并拉第一页。
    LaunchedEffect(selectedTrack, selectedVersion, zeroOnly, bestOnly) {
        filterEpoch++
        val epoch = filterEpoch
        loading = true
        errorText = ""
        entries = emptyList()
        total = 0
        listState.scrollToItem(0)
        val page = withContext(Dispatchers.IO) {
            PaddockClient.fetchDriverPage(
                userId = userId,
                gpIndex = if (selectedTrack == 0) null else selectedTrack - 1,
                version = if (selectedVersion == 0) null else VERSION_CODES.getOrNull(selectedVersion - 1),
                zeroAssistOnly = zeroOnly,
                bestOnly = bestOnly,
                limit = PaddockClient.PAGE_SIZE,
                offset = 0,
            )
        }
        if (epoch != filterEpoch) return@LaunchedEffect
        if (page.ok) {
            profile = page.profile
            entries = page.entries
            total = page.total
        } else {
            errorText = page.error.ifBlank { "加载失败" }
        }
        everLoaded = true
        loading = false
    }

    // 滚到底部自动翻页。key 含 entries.size/total：每加载一页重新评估一次。
    //
    // ⚠️ **`loadingMore` 绝不能放进 key**：在 effect 内把它置 true 会触发重组 →
    //    key 变化 → Compose 取消**当前正在跑的这个协程** → `loadingMore = false`
    //    永不执行 → 底部永远卡在"加载中…"且不再翻页。改用协程内局部变量守卫。
    LaunchedEffect(filterEpoch, entries.size, total, loading) {
        if (!everLoaded || loading) return@LaunchedEffect
        if (entries.size >= total) return@LaunchedEffect   // 已到底
        var busy = false
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (busy) return@collect
                busy = true
                loadingMore = true
                val epoch = filterEpoch
                val offset = entries.size
                val page = withContext(Dispatchers.IO) {
                    PaddockClient.fetchDriverPage(
                        userId = userId,
                        gpIndex = if (selectedTrack == 0) null else selectedTrack - 1,
                        version = if (selectedVersion == 0) null else VERSION_CODES.getOrNull(selectedVersion - 1),
                        zeroAssistOnly = zeroOnly,
                        bestOnly = bestOnly,
                        limit = PaddockClient.PAGE_SIZE,
                        offset = offset,
                    )
                }
                if (epoch == filterEpoch && page.ok) {
                    // 空页 = 服务端已无更多（total 与实际行数不一致时的兜底），
                    // 用"把 total 压到当前条数"表达"到底"，避免无限请求。
                    if (page.entries.isEmpty()) total = entries.size else {
                        entries = entries + page.entries
                        total = page.total
                    }
                }
                loadingMore = false
                busy = false
            }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = profile?.username ?: "车手主页",
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
        Column(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            // ── 资料卡 + 筛选卡（固定在滚动区之外，同排行榜页的筛选卡位置）──
            Column(
                modifier = Modifier
                    .padding(top = innerPadding.calculateTopPadding())
                    .padding(horizontal = 12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    profile?.let { ProfileCard(it) }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        OverlaySpinnerPreference(
                            items = remember {
                                listOf(DropdownItem(text = "全部")) +
                                    TRACK_NAMES.map { DropdownItem(text = it) }
                            },
                            selectedIndex = selectedTrack,
                            title = "赛道",
                            onSelectedIndexChange = { selectedTrack = it },
                        )
                        OverlaySpinnerPreference(
                            items = remember {
                                listOf(
                                    DropdownItem(text = "全部"),
                                    DropdownItem(text = "8.0.6"),
                                    DropdownItem(text = "8.0.4"),
                                )
                            },
                            selectedIndex = selectedVersion,
                            title = "版本",
                            onSelectedIndexChange = { selectedVersion = it },
                        )
                        SwitchPreference(
                            checked = zeroOnly,
                            onCheckedChange = { zeroOnly = it },
                            title = "仅零辅助",
                            summary = "TC 与 ABS 均为关",
                        )
                        SwitchPreference(
                            checked = bestOnly,
                            onCheckedChange = { bestOnly = it },
                            title = "仅个人最佳",
                            summary = "每个赛道版本只显示最快那一圈",
                        )
                    }
                }
            }
            // ── 圈速记录 ──
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp)
                    // 视口顶缘圆角裁切（同排行榜页：行滚出时顶边保持圆角）
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                overscrollEffect = null,
            ) {
                if (!enterSettled || (loading && !everLoaded)) {
                    item(key = "loading") {
                        Text(
                            "加载中…",
                            fontSize = 14.sp,
                            color = colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else if (errorText.isNotEmpty()) {
                    item(key = "error") {
                        Text(
                            errorText,
                            fontSize = 14.sp,
                            color = colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else if (entries.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colorScheme.surfaceContainer, RoundedCornerShape(16.dp)),
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
                } else {
                    itemsIndexed(entries, key = { _, e -> e.lapId }) { i, e ->
                        LapRow(e, rowShape = boardRowShape(i, entries.size))
                    }
                    if (loadingMore) {
                        item(key = "more") {
                            Text(
                                "加载中…",
                                fontSize = 13.sp,
                                color = colorScheme.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    item(key = "footer-space") { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

/**
 * 车手资料卡：头像 + 用户名 + N 号车手 + 三项统计。
 * 视觉与围场主页的信息卡一致（同一套字号/间距/统计行），让"我的主页"和
 * "别人的主页"看起来是同一个东西。
 */
@Composable
private fun ProfileCard(p: PaddockClient.DriverProfile) {
    var avatar by remember(p.avatarUrl) {
        mutableStateOf(p.avatarUrl?.let { PaddockClient.fetchAvatarFromCache(it) })
    }
    LaunchedEffect(p.avatarUrl) {
        val url = p.avatarUrl
        if (url != null && avatar == null) {
            avatar = withContext(Dispatchers.IO) { PaddockClient.fetchAvatar(url) }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Person,
                            modifier = Modifier.size(32.dp),
                            contentDescription = null,
                            tint = colorScheme.onBackground,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = p.username.ifBlank { "车手" },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (p.regSeq > 0) {
                        Text(
                            text = "${p.regSeq} 号车手",
                            fontSize = 13.sp,
                            color = colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("计时赛积分", Modifier.weight(1f))
                StatCell("有成绩赛道", Modifier.weight(1f))
                StatCell("零辅助最快圈", Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatValue(if (p.totalPoints > 0) "${p.totalPoints}" else "暂无", Modifier.weight(1f))
                StatValue("${p.bestCount}", Modifier.weight(1f))
                StatValue("${p.zeroCount}", Modifier.weight(1f))
            }
        }
    }
}

/** 统计列标题（与围场主页同款）。 */
@Composable
private fun StatCell(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        fontSize = 15.sp,
        color = colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = modifier,
        textAlign = TextAlign.Center,
    )
}

/** 统计列值（与围场主页同款）。 */
@Composable
private fun StatValue(value: String, modifier: Modifier = Modifier) {
    Text(
        text = value,
        fontSize = 15.sp,
        modifier = modifier,
        textAlign = TextAlign.Center,
    )
}

/**
 * 一行圈速记录：赛道名（+「最佳」标记）…… 圈速，下方小字显示版本/时间/辅助配置。
 *
 * 为什么两行：赛道名 + 圈速是"扫读"信息（一眼看成绩），版本/时间/辅助配置是
 * "判断含金量"的信息（零辅助的圈更值钱），挤一行会把赛道名截成无意义前缀。
 */
@Composable
private fun LapRow(e: PaddockClient.DriverLap, rowShape: Shape) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colorScheme.surfaceContainer, shape = rowShape)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = e.trackName,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (e.personalBest) {
                    Text(
                        text = "最佳",
                        fontSize = 11.sp,
                        color = colorScheme.onBackground.copy(alpha = 0.55f),
                    )
                }
            }
            Text(
                text = buildString {
                    append(e.versionName)
                    append(" · ")
                    append(e.created)
                    // "缺失"（旧记录/旧版模块没报配置）不展示，避免每行都拖一串"缺失"
                    if (e.pedal.isNotEmpty() && e.pedal != "缺失") {
                        append(" · ")
                        append(e.pedal)
                    }
                    if (e.tc.isNotEmpty() && e.tc != "缺失") {
                        append(" · TC ")
                        append(e.tc)
                    }
                    if (e.abs.isNotEmpty() && e.abs != "缺失") {
                        append(" · ABS ")
                        append(e.abs)
                    }
                },
                fontSize = 12.sp,
                color = colorScheme.onBackground.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = e.lapDisplay,
            fontSize = 15.sp,
            // 圈速列等宽（与排行榜同理由：右对齐后秒数位要对得上）。
            style = MiuixTheme.textStyles.main.merge(DriverTabularDigits),
            color = if (e.zeroAssist) Color(0xFFDAA520) else colorScheme.onBackground,
        )
    }
}

/**
 * 圈速列的等宽数字样式（只开 OpenType `tnum`）。
 *
 * ⚠️ 与 LeaderboardScreen 的 `TabularDigits` 是**同一手法、不同实例**：
 * 那个是文件私有，跨文件复用只能各自声明一份。`tnum` 而非 `FontFamily.Monospace`
 * 的理由见 LeaderboardScreen 里那段长注释（ROM 会把 monospace 族名重定向到系统 sans）。
 */
private val DriverTabularDigits = TextStyle(fontFeatureSettings = "tnum")

/** 页面进入转场时长（miuix NavDriverSpec.PROGRAMMATIC_DURATION_MILLIS，契约值勿随意改）。 */
private const val NAV_ENTER_SETTLE_MILLIS = 500L

/** 距列表末尾还有 N 项时开始预加载下一页（提前量，避免用户看到空白等待）。 */
private const val LOAD_MORE_THRESHOLD = 3
