package tools.alamobile.mod.ui.screen.paddock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.alamobile.mod.PaddockClient
import tools.alamobile.mod.ui.navigation3.LocalNavigator
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.util.Logger
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll

import androidx.compose.runtime.saveable.rememberSaveable

import tools.alamobile.mod.util.CarCatalog


/**
 * 车手主页（2026-10-19 改版）：资料卡 + 三个底栏页（概览 / 计时赛 / 大奖赛）。
 *
 * ## 入口
 * - 围场主页信息卡（整卡可点，右侧指引箭头）
 * - 计时赛排行榜任意一行
 *
 * ## 三页分工（用户 2026-10-19 定案）
 * - **概览**：资料卡（头像/用户名/车手号）+ 个人计时赛成绩卡（两列 × 三组）
 * - **计时赛**：全量圈速记录（分页 + 四维筛选）
 * - **大奖赛**：开发中占位页（页内显示「开发中，敬请期待！」）。
 *   点底栏正常切页，与左右滑动一致（用户 2026-10-19 定案）。
 *
 * ## 分页（用户定案：一次最多 30 条）
 * 滚到接近底部时自动拉下一页；`total` 来自服务端，`entries.size >= total` 即到底。
 *
 * ⚠️ **筛选变更必须作废在途请求**：用 `filterEpoch` 递增做代次标记。否则快速
 *    切换筛选时，旧条件的第 2 页会追加到新条件的第 1 页后面（列表混入不匹配行）。
 *
 * ⚠️ **分页游标用 `entries.size`**，不用页码：服务端按 `created_at DESC, lap_id DESC`
 *    稳定排序，条数即偏移量；用页码在"筛选后总数不足整页"时会算错。
 *
 * ⚠️ **底栏切页不销毁数据状态**：三个页共用同一份 `entries`/`profile`，切到
 *    大奖赛再切回来不重新请求。分页 effect 只在 `tab == 计时赛` 时武装。
 */
@Composable
fun DriverScreen(userId: String) {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    // ⚠️ 顶栏与底栏**共用同一个 backdrop 是安全的**（2026-10-19 查证）：
    //    `BlurredBar` 走 `textureBlur`，它只**读** backdrop（把 backdrop 内容录进
    //    自己每次绘制的离屏 layer），不写 `backdrop.graphicsLayer`。真正会冲突的是
    //    **两处 `layerBackdrop`**——那才共用同一个 graphicsLayer 录制通道。
    //    本页只有内容层一处 `layerBackdrop`（下面 Column），故单实例正确。
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    // 底栏点击切页用（animateScrollToPage 是 suspend）。
    val scope = rememberCoroutineScope()

    // ── 三页 pager（0=概览 1=计时赛 2=大奖赛）──
    // 2026-10-19：从 `when(tab)` 静态切换改为 HorizontalPager，支持左右滑动。
    // `tab` 不再是独立 state，而是**从 pagerState.settledPage 派生**——
    // 两份 state 会漂移（滑到某页但底栏高亮旧页），单一事实源必须是 pager。
    val pagerState = rememberPagerState(initialPage = DRIVER_TAB_OVERVIEW) { DRIVER_TAB_COUNT }
    val tab = pagerState.currentPage

    // 分页 effect 的 key 需要"当前是否在计时赛页"，用派生值参与 key。
    // ⚠️ 用 `currentPage` 而非 `settledPage`：滑动过半即算切换，分页逻辑
    //    在动画结束前就该武装好，避免用户滑到位后还要等一次重组。

    // ── 筛选状态（0 = 全部）──
    var selectedTrack by remember { mutableIntStateOf(0) }   // 0=全部，1..16 → 赛道 0..15
    var selectedVersion by remember { mutableIntStateOf(0) } // 0=全部，1..2 → VERSION_CODES
    var zeroOnly by remember { mutableStateOf(false) }
    var bestOnly by remember { mutableStateOf(false) }
    // 排序方式（0..3 → SORT_KEYS）；默认 0 = 时间降序，与服务端缺省行为一致。
    var sortIndex by remember { mutableIntStateOf(0) }

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

    // 首屏 + 筛选/排序变更：重置列表并拉第一页。
    // ⚠️ `sortIndex` 必须在 key 里：排序变了等同换了一套查询，旧列表整体作废。
    LaunchedEffect(selectedTrack, selectedVersion, zeroOnly, bestOnly, sortIndex) {
        filterEpoch++
        val epoch = filterEpoch
        loading = true
        errorText = ""
        entries = emptyList()
        total = 0
        // ⚠️ **这里绝不能调 `listState.scrollToItem(0)`**（2026-10-19 实机修复）：
        //    它是 **suspend** 函数，会挂起直到 LazyColumn 完成测量。而车手主页的
        //    默认页是「概览」——那个 LazyColumn 根本没挂这个 `listState`（只有计时赛页
        //    的列表用它）⇒ 协程卡死在 scrollToItem，后面的请求代码永远执行不到，
        //    表现为「概览一直显示加载中，切到计时赛再切回来才加载出来」
        //    （切页时列表挂上 → 挂起解除 → 请求才发出）。
        //    也不需要手动归零：`entries = emptyList()` 已让列表项数变 0，
        //    Compose 会自动把滚动位置 clamp 回顶部。
        val page = withContext(Dispatchers.IO) {
            PaddockClient.fetchDriverPage(
                userId = userId,
                gpIndex = if (selectedTrack == 0) null else selectedTrack - 1,
                version = if (selectedVersion == 0) null else VERSION_CODES.getOrNull(selectedVersion - 1),
                zeroAssistOnly = zeroOnly,
                bestOnly = bestOnly,
                sort = SORT_KEYS.getOrNull(sortIndex),
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
    //
    // ⚠️ **`tab` 必须在 key 里**：不在计时赛页时不武装（否则在概览页也会因为
    //    listState 布局为 0 而误判"滚到底"）。切回计时赛时 key 变化 → 重新武装。
    LaunchedEffect(filterEpoch, entries.size, total, loading, tab) {
        if (tab != DRIVER_TAB_TIMETRIAL) return@LaunchedEffect
        if (!everLoaded || loading) return@LaunchedEffect
        if (entries.size >= total) return@LaunchedEffect   // 已到底
        var busy = false
        snapshotFlow {
            val info = listState.layoutInfo
            // ⚠️ 布局未完成时必须判否，不能把 `lastOrNull() == null` 当成"已到底"
            //    （2026-10-18 与 LeaderboardScreen 同批修复）：旧写法回落到 -1，
            //    而 `totalItemsCount` 布局前为 0 ⇒ `-1 >= 0 - 3` 恒真 ⇒ effect 每次
            //    重启（entries.size 变化即在 key 里）都立即翻页，一次性拉完全部数据。
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            // 基准用**数据条数**而非 totalItemsCount：后者含 footer 等装饰 item。
            last >= entries.size - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (busy) return@collect
                busy = true
                // ⚠️ **`loadingMore` 必须用 try/finally 复位**（2026-10-19 实机修复：
                //    "记录不足 30 条时最后一条下方持续显示加载中…"）：
                //    置 true 后请求在 `withContext(Dispatchers.IO)` 挂起，此时若 effect
                //    的 key（filterEpoch / tab / entries.size / total）变化，Compose 会
                //    **取消整个协程** ⇒ `loadingMore = false` 那行永远执行不到。
                //    而新启动的 effect 因为 `entries.size >= total` 立即 return，
                //    再没人复位 ⇒ 底部永久卡在"加载中…"。
                //    记录多时新 effect 会继续翻页、状态被复用所以看不出来；
                //    记录少于 PAGE_SIZE(30) 时必然暴露。try/finally 在取消时同样执行。
                loadingMore = true
                val epoch = filterEpoch
                val offset = entries.size
                // 分页诊断日志（与 LeaderboardScreen 同款，2026-10-18 补齐）：
                // ⚠️ **判据是相邻两条的时间间隔，不是条数**——正常翻页的间隔 ≈ 用户
                //    滚动耗时（秒级）；间隔 <200ms 的连发说明哨兵值 bug 复发
                //    （`?: -1` 参与 `>= size - N` 恒真 ⇒ effect 每次重启都立即翻页）。
                // ⚠️ 必须带筛选指纹：本页有四维筛选，同一个 offset 在不同筛选下是不同查询，
                //    只打 offset 拿到日志也分不清是哪一条。
                Logger.i(
                    TAG_DRIVER,
                    "load more: offset=$offset total=$total sort=${SORT_KEYS.getOrNull(sortIndex)} " +
                        "track=$selectedTrack ver=$selectedVersion zero=$zeroOnly best=$bestOnly",
                )
                try {
                    val page = withContext(Dispatchers.IO) {
                        PaddockClient.fetchDriverPage(
                            userId = userId,
                            gpIndex = if (selectedTrack == 0) null else selectedTrack - 1,
                            version = if (selectedVersion == 0) null else VERSION_CODES.getOrNull(selectedVersion - 1),
                            zeroAssistOnly = zeroOnly,
                            bestOnly = bestOnly,
                            sort = SORT_KEYS.getOrNull(sortIndex),
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
                } finally {
                    // 协程被取消时也执行（CancellationException 穿透 finally 后继续传播）。
                    loadingMore = false
                    busy = false
                }
            }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    // ⚠️ 标题恒为「车手主页」，**不用用户名**（用户 2026-10-19 定案）：
                    //    用户名已经在资料卡里以粗体显示，标题再放一遍是重复信息，
                    //    且长昵称会把标题栏挤成省略号。
                    title = "车手主页",
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
        bottomBar = {
            BlurredBar(backdrop) {
                NavigationBar(color = barColor) {
                    DriverTab.entries.forEachIndexed { index, d ->
                        NavigationBarItem(
                            modifier = Modifier.weight(1f),
                            icon = d.icon,
                            label = d.label,
                            selected = tab == index,
                            onClick = {
                                // 三个 tab 一视同仁：点击即切页（用户 2026-10-19 定案）。
                                // 大奖赛是开发中占位页（页内已有「开发中，敬请期待！」），
                                // 点击切过去与左滑到达行为一致——不能"点不动只能滑"，
                                // 那会让底栏按钮看起来像失灵。
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                        )
                    }
                }
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            // 三页左右滑动切换（2026-10-19 用户要求）。
            // ⚠️ **大奖赛页在 pager 里必须有内容**：底栏点击与左右滑动都能到达
            //    该页，页内必须给出「开发中，敬请期待！」占位，否则是空白页。
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxHeight(),
                // 只在真正可滑的两页间滑动；大奖赛页滑过去会显示占位文案。
                userScrollEnabled = true,
            ) { page ->
                when (page) {
                    DRIVER_TAB_TIMETRIAL -> TimetrialTab(
                        profile = profile,
                        entries = entries,
                        total = total,
                        loading = loading,
                        loadingMore = loadingMore,
                        everLoaded = everLoaded,
                        enterSettled = enterSettled,
                        errorText = errorText,
                        listState = listState,
                        scrollBehavior = scrollBehavior,
                        innerPadding = innerPadding,
                        selectedTrack = selectedTrack,
                        selectedVersion = selectedVersion,
                        zeroOnly = zeroOnly,
                        bestOnly = bestOnly,
                        sortIndex = sortIndex,
                        onApplyFilter = { track, version, zero, best, sort ->
                            selectedTrack = track
                            selectedVersion = version
                            zeroOnly = zero
                            bestOnly = best
                            sortIndex = sort
                        },
                    )
                    DRIVER_TAB_GRAND_PRIX -> GrandPrixPlaceholder(innerPadding)
                    else -> OverviewTab(
                        profile = profile,
                        loading = loading && !everLoaded,
                        errorText = errorText,
                        scrollBehavior = scrollBehavior,
                        innerPadding = innerPadding,
                    )
                }
            }
        }
    }
}

/**
 * 大奖赛占位页：底栏点击或左右滑到这里时显示的「开发中」提示。
 *
 * 该页尚未实现，但**底栏点击与滑动行为必须一致**（用户 2026-10-19 定案）——
 * 只让滑动可达而点击无反应，会让底栏按钮看起来像失灵。
 */
@Composable
private fun GrandPrixPlaceholder(innerPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "开发中，敬请期待！",
            fontSize = 15.sp,
            color = colorScheme.onBackground.copy(alpha = 0.5f),
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 概览页
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 概览页：资料卡 + 个人计时赛成绩卡（两列 × 三组）。
 *
 * 用户 2026-10-19 定案：把原先塞在资料卡里的三列统计**移出**，改成独立卡片、
 * 两列形式，并按"个人计时赛"分组。理由是资料卡是"身份"、成绩卡是"战绩"，
 * 混在一张卡里既挤又难扫读。
 */
@Composable
private fun OverviewTab(
    profile: PaddockClient.DriverProfile?,
    loading: Boolean,
    errorText: String,
    scrollBehavior: ScrollBehavior,
    innerPadding: PaddingValues,
) {
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
        item(key = "overview") {
            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                // ⚠️ **不要用 `Arrangement.spacedBy` 给「小标题 + 卡片」分组排版**
                //    （2026-10-19 用户反馈「间距不对」的根因）：miuix 的小标题规范是
                //    **上远下近**——卡片自带 `padding(bottom = 12.dp)`，SmallTitle 内边距
                //    上下各 8dp ⇒ 上方 12+8=20dp、下方 8dp（见 miuix 官方
                //    `references/miuix/docs/zh_CN/components/smalltitle.md` 的组合示例与 KernelSU 的
                //    `AppProfileMiuix.kt`）。若外面再套 `spacedBy(12.dp)`，上下会各加
                //    12dp 变成 20/20 对称，小标题就"悬浮"在两个卡片正中间了。
                //    ⇒ 间距一律由各元素自己的 padding 表达。
            ) {
                when {
                    loading -> Text(
                        "加载中…",
                        fontSize = 14.sp,
                        color = colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(16.dp),
                    )
                    errorText.isNotEmpty() -> Text(
                        errorText,
                        fontSize = 14.sp,
                        color = colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(16.dp),
                    )
                    profile != null -> {
                        // 卡片自带底部 12dp —— 这是「小标题上方间距」的一半，
                        // 另一半由 SmallTitle 自己的 8dp 上内边距提供（合计 20dp）。
                        ProfileCard(profile, modifier = Modifier.padding(bottom = 12.dp))
                        // 「个人计时赛」是成绩卡的小标题，**在卡片之外**（用户 2026-10-19 定案：
                        // 原先是卡片内的标题行，移到上方作为分组小标题）。
                        //
                        // ⚠️ 必须用 miuix 的 [`SmallTitle`] 而不是手写 `Text`（用户 2026-10-19
                        //    反馈「不符合 miuix 小标题规范」）：它固定走 `textStyles.subtitle`
                        //    + `onBackgroundVariant`，字号与颜色都属于主题，能跟全 App 其他
                        //    分组标题保持一致；手写 14sp + alpha 只是数值上接近的仿制品。
                        //
                        // ⚠️ `textColor` 覆盖成中性灰（用户 2026-10-19 定案「不需要动态取色，
                        //    灰色就行」）：默认的 `onBackgroundVariant` 在本项目的
                        //    `lightColorScheme()` 里是 `0xFF8C93B0`（偏蓝的蓝灰），不是中性灰。
                        //    改用 `onBackground.copy(alpha = 0.6f)` —— 与资料卡「N 号车手」、
                        //    统计列标题等本项目既有的次要文字同色。
                        //
                        // ⚠️ `insideMargin` 横向覆盖成 16dp：默认 28dp 是为「页面无横向内边距 +
                        //    卡片自带 horizontal 12dp」的结构设计的；本项目 LazyColumn 已有
                        //    12dp 横向内边距，12+16=28 与卡片内文字（12 页面 + 16 卡内）左对齐。
                        SmallTitle(
                            text = "个人计时赛",
                            textColor = colorScheme.onBackground.copy(alpha = 0.6f),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        )
                        StatsCard(profile)
                    }
                }
            }
        }
    }
}

/**
 * 车手资料卡：头像 + 用户名 + N 号车手。
 *
 * 2026-10-19：三列统计已移出到 [StatsCard]（用户定案），本卡只留身份信息。
 */
@Composable
private fun ProfileCard(
    p: PaddockClient.DriverProfile,
    modifier: Modifier = Modifier,
) {
    var avatar by remember(p.avatarUrl) {
        mutableStateOf(p.avatarUrl?.let { PaddockClient.fetchAvatarFromCache(it) })
    }
    LaunchedEffect(p.avatarUrl) {
        val url = p.avatarUrl
        if (url != null && avatar == null) {
            avatar = withContext(Dispatchers.IO) { PaddockClient.fetchAvatar(url) }
        }
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp),
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
    }
}

/**
 * 个人计时赛成绩卡：两列 × 三组（总/版本/纪录）。
 *
 * ## 名次的奖牌形式
 * 前三名沿用排行榜的 🥇🥈🥉（[rankLabel]），其余显示数字——用户 2026-10-19 定案。
 * 前三名 emoji 放大到 22.5sp（与排行榜行完全一致），数字名次 15sp。
 *
 * ## 小标题在卡片之外
 * 「个人计时赛」已移到本卡**上方**（用户 2026-10-19 定案），由 [OverviewTab] 渲染。
 * 卡片内不再有标题行，三组数据直接是 `Column` 的直接子项。
 */
@Composable
private fun StatsCard(p: PaddockClient.DriverProfile) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── 三组两列数据 ──
            // 每组 = 左右两个「标题 + 值」列，**两列各自居中对齐**：
            // 左列中心落在卡片宽度的 1/3 处、右列中心落在 2/3 处（用户定案）。
            // 实现 = 两列各 weight(1f) + textAlign = Center，卡片水平内边距 16dp。
            StatPair(
                leftTitle = "总积分",
                leftValue = pointsText(p.totalPoints),
                rightTitle = "总排名",
                rightRank = p.totalRank,
            )
            StatPair(
                leftTitle = "版本积分",
                leftValue = pointsText(p.versionPoints),
                rightTitle = "版本排名",
                rightRank = p.versionRank,
            )
            StatPair(
                leftTitle = "全服最快圈数量",
                leftValue = countText(p.recordCount),
                rightTitle = "最快圈数量排名",
                rightRank = p.recordRank,
            )
        }
    }
}

/**
 * 一组两列统计：左列"标题 + 数值"，右列"标题 + 名次"，两列**各自居中**。
 *
 * ## 对齐（2026-10-19 用户定案 + 修复）
 * 两列各 `weight(1f)` + `textAlign = Center` ⇒ 左列中心在卡片 1/3 处、右列在 2/3 处。
 *
 * ⚠️ **对齐靠结构对称，不靠调字号**（2026-10-19 三次迭代后的定案）：
 *    左右两格都走 [`StatCell`]——标题固定 20dp、值固定 30dp，值格是
 *    `Box(contentAlignment = Center)`。**两个 Column 的子元素高度完全相同**，
 *    值格的 y 坐标必然相等，此时 17sp 数字与 22.5sp 奖牌 emoji 各自在
 *    30dp 格内居中即可对齐，字号可以保持差异。
 *
 *    前两次修错的路径（勿重蹈）：① 左边 `Text` 直接加 `heightIn`（文本顶部对齐）
 *    而右边用 `Box(Center)` —— 容器不同，必然错开；② 归因到"字号不同"并把
 *    emoji 压回 17sp —— 对齐问题解决了但奖牌变小，用户立刻反馈。
 *    **真正的不变量是"两侧结构相同"，不是"字号相同"。**
 *
 * 名次为 null / 0（无成绩）时显示「暂无」，与积分的 0 同口径——用户 2026-10-19 定案：
 * 「0」和「-」都改为「暂无」。
 */
@Composable
private fun StatPair(
    leftTitle: String,
    leftValue: String,
    rightTitle: String,
    rightRank: Long?,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        StatCell(
            title = leftTitle,
            modifier = Modifier.weight(1f),
        ) {
            // ⚠️ 空值「暂无」与数字**同色**（2026-10-19 用户定案：改用黑色）。
            //    历史：首版左值走默认主题色（黑）、右侧名次空值显式 alpha=0.5（灰），
            //    同一张卡里两个「暂无」不同色；随后统一为灰；最终用户要求统一改黑。
            //    颜色只在这里定义一次，左右两侧都走 [`StatValueText`]。
            StatValueText(text = leftValue)
        }
        StatCell(
            title = rightTitle,
            modifier = Modifier.weight(1f),
        ) {
            val rank = rightRank?.takeIf { it > 0 }?.toInt()
            if (rank == null) {
                StatValueText(text = EMPTY_VALUE)
            } else {
                Text(
                    text = rankLabel(rank),
                    // 前三名 emoji 放大 50%（17→22.5sp），与排行榜行同比例
                    // （用户 2026-10-19 反馈"奖牌变小了"后恢复）。
                    // ⚠️ 放大**不会**破坏对齐：值格是固定 30dp 高的
                    //    `Box(contentAlignment = Center)`，22.5sp 在 30dp 内仍居中，
                    //    与左侧 17sp 数字共用同一视觉中心线。
                    fontSize = if (rank <= 3) 22.5.sp else 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = MiuixTheme.textStyles.main.merge(DriverTabularDigits),
                )
            }
        }
    }
}

/**
 * 一格统计：**标题行固定高度 + 值格固定高度**，左右两格结构完全一致。
 *
 * ⚠️ 2026-10-19 对齐修复（三次迭代后的定案）：
 *    - 首版左右容器不同（左 `Text+heightIn` 顶部对齐、右 `Box(Center)` 居中）→ 错开；
 *    - 二版两边都 `Box(Center)`，但**标题行高度随字数/字体度量浮动**
 *      （「总积分」4 字 vs「总排名」3 字），两个 Column 的值格起始 y 不同 → 仍错开；
 *    - 定案 = 标题与值**各自固定高度**，两侧结构逐字相同，值格的 y 坐标必然相等。
 *
 * 标题高度按 13sp 单行的实际行高取 20dp（13sp × 1.5 ≈ 19.5dp，取整），
 * 值格 30dp 容得下 17sp 粗体与 emoji。
 */
@Composable
private fun StatCell(
    title: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.height(STAT_TITLE_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.height(STAT_VALUE_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            value()
        }
    }
}

/** 标题行固定高度（13sp 单行行高）。 */
private val STAT_TITLE_HEIGHT = 20.dp

/** 统计值格固定高度（容得下 17sp 粗体数字与奖牌 emoji）。 */
private val STAT_VALUE_HEIGHT = 30.dp

/** 无数据时的统一占位文案。 */
private const val EMPTY_VALUE = "暂无"

/**
 * 统计值文本：**空值（「暂无」）与数字同色**，一律用主题前景色。
 *
 * 2026-10-19 用户定案：空值不再用灰色（此前 `empty` 参数走 `alpha = 0.5f`）。
 * 因此不再需要 `empty` 参数——颜色只有一个分支，保留参数只会让人误以为
 * 「暂无」还有特殊配色。若将来要恢复弱化效果，在这里加分支即可。
 */
@Composable
private fun StatValueText(text: String) {
    Text(
        text = text,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = colorScheme.onBackground,
        style = MiuixTheme.textStyles.main.merge(DriverTabularDigits),
    )
}

/** 积分文案：0 分显示「暂无」（与围场主页信息卡同口径）。 */
private fun pointsText(points: Long): String = if (points > 0) "$points" else "暂无"

/** 计数文案：0 显示「暂无」（用户 2026-10-19 定案：「0」改为「暂无」）。 */
private fun countText(count: Long): String = if (count > 0) "$count" else "暂无"

// ═══════════════════════════════════════════════════════════════════════════
// 计时赛页
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 计时赛页：筛选入口卡 + 全量圈速记录（分页）。
 *
 * **筛选改为「入口卡 + 弹窗」**（2026-10-19 用户定案）：原来四维控件直接铺在页首，
 * 占掉近半屏且随页面滚动时挡视线。现在页首只有一张「筛选条件」卡（漏斗图标 +
 * 右侧箭头），点开才在弹窗里改条件，点「确认」才生效。
 *
 * ⚠️ **筛选卡随列表滚动，不固定**（用户 2026-10-19 定案）：排行榜那种钉在滚动区
 *    之外的做法在这里不需要——筛选已收进弹窗，页首只剩一张入口卡，随内容滚走
 *    反而给列表让出更多可视高度。
 *
 * ⚠️ **弹窗内是草稿态**：打开时把当前筛选拷进局部 state，只有点「确认」才回调
 *    [onApplyFilter] 写回全局。点「取消」/点窗外 = 草稿丢弃，列表不变。
 *    直接改全局会让用户滑一下 spinner 就触发一次重新请求。
 */
@Composable
private fun TimetrialTab(
    profile: PaddockClient.DriverProfile?,
    entries: List<PaddockClient.DriverLap>,
    total: Int,
    loading: Boolean,
    loadingMore: Boolean,
    everLoaded: Boolean,
    enterSettled: Boolean,
    errorText: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollBehavior: ScrollBehavior,
    innerPadding: PaddingValues,
    selectedTrack: Int,
    selectedVersion: Int,
    zeroOnly: Boolean,
    bestOnly: Boolean,
    sortIndex: Int,
    onApplyFilter: (track: Int, version: Int, zero: Boolean, best: Boolean, sort: Int) -> Unit,
) {
    // 弹窗双变量：`filterMounted` 管组合树挂载，`filterShow` 驱动显示参数。
    // 单变量会让退出动画被跳过（弹窗瞬间消失）——项目弹窗铁律，见 CLAUDE.md。
    var filterMounted by remember { mutableStateOf(false) }
    var filterShow by remember { mutableStateOf(false) }
    // 草稿态：弹窗打开时从当前筛选快照一份，确认才写回。
    var draftTrack by remember { mutableIntStateOf(selectedTrack) }
    var draftVersion by remember { mutableIntStateOf(selectedVersion) }
    var draftZero by remember { mutableStateOf(zeroOnly) }
    var draftBest by remember { mutableStateOf(bestOnly) }
    var draftSort by remember { mutableIntStateOf(sortIndex) }

    fun openFilterDialog() {
        draftTrack = selectedTrack
        draftVersion = selectedVersion
        draftZero = zeroOnly
        draftBest = bestOnly
        draftSort = sortIndex
        filterMounted = true
        filterShow = true
    }

    Box {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding(),
            ),
            overscrollEffect = null,
        ) {
            // ── 筛选入口卡（随列表滚动，2026-10-19 用户定案不固定）──
            item(key = "filter-entry") {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = "筛选条件",
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.FilterAlt,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = { openFilterDialog() },
                        )
                    }
                }
            }
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
                    // 分隔线挂在**第 2 行及之后**（首行上方是筛选卡，多一条线没有意义）。
                    LapRow(e, rowShape = boardRowShape(i, entries.size), showDivider = i > 0)
                }
                if (loadingMore && entries.size < total) {
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
        if (filterMounted) {
            FilterDialog(
                show = filterShow,
                track = draftTrack,
                version = draftVersion,
                zeroOnly = draftZero,
                bestOnly = draftBest,
                sortIndex = draftSort,
                onTrack = { draftTrack = it },
                onVersion = { draftVersion = it },
                onZeroOnly = { draftZero = it },
                onBestOnly = { draftBest = it },
                onSort = { draftSort = it },
                onCancel = { filterShow = false },
                onConfirm = {
                    onApplyFilter(draftTrack, draftVersion, draftZero, draftBest, draftSort)
                    filterShow = false
                },
                onDismissFinished = { filterMounted = false },
            )
        }
    }
}

/**
 * 筛选条件弹窗：赛道 / 版本 / 仅零辅助 / 仅个人最佳 / 排序方式。
 *
 * 左「取消」（灰）右「确认」（蓝），与项目其余确认弹窗一致。
 * `onDismissRequest` 走取消语义——点窗外等同放弃草稿。
 */
@Composable
private fun FilterDialog(
    show: Boolean,
    track: Int,
    version: Int,
    zeroOnly: Boolean,
    bestOnly: Boolean,
    sortIndex: Int,
    onTrack: (Int) -> Unit,
    onVersion: (Int) -> Unit,
    onZeroOnly: (Boolean) -> Unit,
    onBestOnly: (Boolean) -> Unit,
    onSort: (Int) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "筛选条件",
        onDismissRequest = onCancel,
        onDismissFinished = onDismissFinished,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ⚠️ 弹窗里的 spinner 必须 `renderInRootScaffold = false`：
                //    弹窗内容本身挂在 DialogLayout 里，默认值会让下拉菜单渲染到
                //    **根 Scaffold** 而非弹窗层，菜单被弹窗遮住或位置错位。
                //    项目内暂无弹窗内 spinner 先例，这是按 miuix 参数语义取的稳妥值。
                OverlaySpinnerPreference(
                    items = remember {
                        listOf(DropdownItem(text = "全部")) +
                            TRACK_NAMES.map { DropdownItem(text = it) }
                    },
                    selectedIndex = track,
                    title = "赛道",
                    onSelectedIndexChange = onTrack,
                    renderInRootScaffold = false,
                )
                OverlaySpinnerPreference(
                    items = remember {
                        listOf(
                            DropdownItem(text = "全部"),
                            DropdownItem(text = "8.0.6"),
                            DropdownItem(text = "8.0.4"),
                        )
                    },
                    selectedIndex = version,
                    title = "版本",
                    onSelectedIndexChange = onVersion,
                    renderInRootScaffold = false,
                )
                // 两个开关**不带 summary**（用户 2026-10-19 定案：描述删掉）。
                SwitchPreference(
                    checked = zeroOnly,
                    onCheckedChange = onZeroOnly,
                    title = "仅零辅助",
                )
                SwitchPreference(
                    checked = bestOnly,
                    onCheckedChange = onBestOnly,
                    title = "仅个人最佳",
                )
                OverlaySpinnerPreference(
                    items = remember { SORT_LABELS.map { DropdownItem(text = it) } },
                    selectedIndex = sortIndex,
                    title = "排序方式",
                    onSelectedIndexChange = onSort,
                    renderInRootScaffold = false,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "确认",
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

/**
 * 一行圈速记录，**三行**（2026-10-19 用户定案改版）：
 *
 * ```
 * 🇮🇹 蒙扎国家赛车场  最佳                    1:11.189   ← 赛道名 + 圈速同行
 * 时间：2026-10-06  21:24   版本：8.0.6                 ← 间隔 2 字符（日期/时分再×2）
 * 踏板模式：双踏板  TC：关  ABS：关                        ← 间隔 2 字符
 * ```
 *
 * 为什么这样排：
 * - **圈速与赛道名同行**：圈速是扫读信息，和赛道名一起构成"一眼看成绩"的第一行；
 *   旧实现把圈速放在右侧并垂直居中，视觉上落在两行之间、左缘贴着详情行，
 *   既读不出层级也让详情被挤成半宽。
 * - **详情占满卡片宽度**：旧实现详情在 `Column(weight(1f))` 里，右侧被圈速列
 *   永久吃掉一截；三行结构下圈速已上移，详情不再有横向对手。
 * - **详情用「间隔 2 字符」而非等分栏**（2026-10-19 用户第三次定案，推翻了
 *   上一版的 0、1/2、1/3 固定分栏）：等分栏在窄屏上会给「时间」这种长字段
 *   挤出省略号，而「版本：8.0.6」却留一大片空白；按内容流式排列 + 固定字符
 *   间隔，信息密度与可读性都更好。字符间隔用 `Spacer(Modifier.width(...))`
 *   按**正文实测字宽**计算，不写死 dp——字号/字体一变就自动跟着变。
 */
@Composable
private fun LapRow(e: PaddockClient.DriverLap, rowShape: Shape, showDivider: Boolean = false) {
    // 详情弹窗的挂载状态（2026-10-26）。
    // ⚠️ 双变量模式（mounted + show）：`show` 驱动弹窗的进出动画，
    //    `mounted` 控制是否留在组合树里。只用一个变量会在关闭时跳过退场动画
    //    （弹窗瞬间消失）——这是项目里所有 miuix 弹窗的统一约定。
    var detailMounted by rememberSaveable { mutableStateOf(false) }
    var detailShow by rememberSaveable { mutableStateOf(false) }

    Column(
        // 背景色在**外层**、分隔线在背景之内：若把 divider 放到背景之外，它会落在
        // 页面底色上、与卡片色断开，视觉上像"卡片之间有条缝"而不是"卡片内分行"。
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colorScheme.surfaceContainer, shape = rowShape)
            // 整行可点：打开圈速详情弹窗（2026-10-26 用户定案——
            // 原来卡片上直接铺 3 行参数，改为点击看详情，卡片只留赛道/圈速/时间）。
            .clickable {
                detailMounted = true
                detailShow = true
            },
    ) {
        // 相邻记录之间的分隔线（首行不画——上方是筛选卡，画了会多出一条无意义的线）。
        // 左右 16dp 缩进 = 正文左缘，让分隔线只横跨内容区、不贴卡片边。
        // 用 miuix `HorizontalDivider`（默认 `dividerLine` 色 + 0.75dp），
        // 不手写 Canvas——主题色随深浅模式自动切。
        if (showDivider) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 金色流光样式：只对零辅助圈求值（普通圈以 null 传入 → 完全走原样式，零开销）。
            // ⚠️ 与排行榜同一条红线：**不能再传 `color =`**——miuix Text 的 color 会覆盖
            //    brush，金光直接消失（见 LeaderboardScreen.goldShimmerStyle 注释）。
            val goldStyle = if (e.zeroAssist) goldShimmerStyle(MiuixTheme.textStyles.main) else null
            // ── 第 1 行：赛道名（+「最佳」）…… 圈速 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 左侧分组整体吃满剩余宽度（weight 在外层），圈速因此恒贴右缘；
                // 「最佳」在组内紧跟赛道名（内层 Text 用 fill=false 只占自身宽度）。
                // ⚠️ 不要把 weight 放在赛道名上再补一个 Spacer(weight)：两个加权项
                //    各分一半，赛道名短时那一半不会回收，圈速会被推到卡片中间而非右缘。
                Row(
                    modifier = Modifier.weight(1f),
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
                    if (e.hidden) {
                        // 「已屏蔽」红标（2026-10-20）：该圈被本人「屏蔽缺失配置记录」
                        // 移出了榜单。服务端已强制与 personal_best 互斥
                        // （driver.rs 的 `personal_best && !hidden`），故这里先判 hidden。
                        Text(
                            text = "已屏蔽",
                            fontSize = 11.sp,
                            // 用主题 error 色而非硬编码红：深浅模式都有足够对比度。
                            color = colorScheme.error,
                        )
                    } else if (e.personalBest) {
                        Text(
                            text = "最佳",
                            fontSize = 11.sp,
                            // 蓝色取主题 primary（light 0xFF3482FF / dark 0xFF277AF7），
                            // 不硬编码——深色模式下同一个蓝仍可读。
                            color = colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = e.lapDisplay,
                    fontSize = 15.sp,
                    // 圈速列等宽（与排行榜同理由：右对齐后秒数位要对得上）。
                    // 零辅助圈速走金色流光，与排行榜金标同一份实现（见 goldShimmerStyle）。
                    style = (goldStyle ?: MiuixTheme.textStyles.main).merge(DriverTabularDigits),
                    // ⚠️ 金标时**必须**是 Unspecified：传任何具体色都会覆盖 style 里的 brush，
                    //    表现为"纯静态金色"（2026-10-19 用户报的正是这个 bug）。
                    color = if (e.zeroAssist) Color.Unspecified else colorScheme.onBackground,
                )
            }
            // ── 第 2 行：裸时间（2026-10-26 用户定案）──
            // 去掉了「时间：」前缀与整条第 3 行（踏板/TC/ABS）——这些参数全部
            // 移进点击后的「圈速详情」弹窗。卡片只留"一眼扫读"的三要素：
            // 赛道 + 圈速（第 1 行）、时间（第 2 行）。
            // 日期与时分之间保留双空格：单空格在 12sp 下只有 3dp 左右，
            // 日期和时间会糊成一串数字。
            Text(
                text = e.created.replace(" ", "  "),
                fontSize = 12.sp,
                color = colorScheme.onBackground.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // 圈速详情弹窗（2026-10-26）。挂载在 LapRow 内，由双变量驱动。
    //
    // ⚠️ **`onDismissRequest` 与 `onDismissFinished` 必须是两个不同动作**
    //    （2026-10-26 实机报「退出没有动画」，项目里这是第 N 次踩）：
    //    - `onDismissRequest`（点窗外/返回键）只翻 `show = false` → 播放退场动画；
    //    - `onDismissFinished`（动画真正结束）才置 `mounted = false` 卸挂载。
    //    两者合并成同一个 lambda 会让挂载在动画开始的那一帧就消失，
    //    退场动画没有播放窗口 ⇒ 弹窗瞬间消失。
    if (detailMounted) {
        LapDetailDialog(
            entry = e,
            show = detailShow,
            onDismissRequest = { detailShow = false },
            onDismissFinished = { detailMounted = false },
        )
    }
}

/**
 * 圈速详情弹窗（2026-10-26 用户定案）。
 *
 * 布局要求：**左侧项目名左对齐、右侧值列左对齐，一列下来很整齐；双值项的
 * 第二个值也相互左对齐**。因此不能用流式排列（间隔 N 字符那种），必须是
 * **两列网格**：第一列固定宽度（取最长项目名的实测宽度），第二列起始 x 恒定。
 * 双值项内部再用两个固定宽度子列（前值 / 后值），保证「65」「58」上下对齐。
 *
 * ⚠️ 列宽必须用 `TextMeasurer` 实测，**不能写死 dp**——用户改系统字号/字体后
 *    12sp 的实际字宽会变，写死会让第二列起始位置错位。
 * ⚠️ 缺失一律显示「缺失」（用户 2026-10-26 定案：双值项两个值位**各显示一次**
 *    「缺失」），不要跳过不渲染——那样"旧记录没数据"和"数据恰好正常"无法区分。
 */
@Composable
private fun LapDetailDialog(
    entry: PaddockClient.DriverLap,
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    // 车型展示名：服务端只给 carId+era 整数，这里用 CarCatalog 反查渲染
    // 「2026 梅赛德斯-AMG W17」。查不到（未知车/缺失）→ null → 显示「缺失」。
    val carLabel = remember(entry.carId, entry.carEra) {
        val id = entry.carId ?: return@remember null
        val car = CarCatalog.byCarId(id) ?: return@remember null
        // 年代前缀：2023 地效 / 2026 主动空力。用 CarCatalog 的 era 而非
        // 服务端的 car_era（两者理论上一致，但 CarCatalog 是单一事实源）。
        val year = when (car.era) {
            CarCatalog.Era.GROUND_EFFECT -> "2023"
            CarCatalog.Era.DOUBLE_DRS -> "2026"
        }
        val team = car.teamLabel
        val model = car.realModel
        if (model != null) "$year $team $model" else "$year $team"
    }

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // **整个弹窗统一套 `tnum`**（用户 2026-10-26 定案）：项目名、值、标题全部等宽，
    // 目的是**字符级对齐**——小数点、数字位严格竖直对齐，而不是只让三列左缘对齐。
    //
    // ⚠️ 用 `tnum` 而非 `FontFamily.Monospace`：后者是 `GenericFontFamily("monospace")`，
    //    Compose 走 `Typeface.create("monospace", NORMAL)` 即**按族名运行时查询**，
    //    ColorOS / Flyme 实测把它重定向到系统 sans（相邻数字 advance 不等，
    //    见 LeaderboardScreen `TabularDigits` 的长注释）。`tnum` 是字体自带的
    //    数字变体，不换字体只换数字字形，对 ROM 免疫；不带该特性的字体静默忽略、
    //    退化为比例数字（不更差）。
    // ⚠️ 中文标签本来就不是数字，`tnum` 对它们无影响——这不是缺陷，
    //    中文字形在等宽语境下只需保证标签列定宽即可。
    val labelStyle = MiuixTheme.textStyles.main.copy(fontSize = 13.sp)
        .merge(DriverTabularDigits)
    val valueStyle = MiuixTheme.textStyles.main.copy(fontSize = 13.sp)
        .merge(DriverTabularDigits)
    val labelWidth = remember(measurer, density, labelStyle) {
        // 项目名仍用固定宽度：它必须与值列起始 x 对齐，而项目名长度是有限的
        // 已知集合（下面这个列表就是全集，改文案时自动跟着变）。
        val longest = listOf(
            "踏板模式", "刹车平衡", "悬架刚度", "防倾杆", "底盘高度", "下压力",
            "外倾角", "前束角", "赛道", "圈速", "时间", "版本", "TC", "ABS", "车型",
        ).maxByOrNull { measurer.measure(it, labelStyle, softWrap = false).size.width }
            ?: "踏板模式"
        with(density) { measurer.measure(longest, labelStyle, softWrap = false).size.width.toDp() }
    }

    // 数值格式：**原样呈现，去掉尾随零**（用户 2026-10-26 定案）。
    //
    // 为什么不做"整数项取整 / 角度项两位小数"的分档处理：
    // ① 游戏 UI 显示整数是 `fcvtzs`（**向零截断**）的结果
    //    （`UpdateSlidersStrings` 0x18AD158 `fcvtzs w9,s0`），底层存的是浮点——
    //    实测刹车平衡调「58」实际是 `58.709`。按整数显示会丢精度。
    // ② 后续要做「应用调教」功能，回写到游戏时必须是原始 float，
    //    显示层若截断会让人误以为存的是整数。
    // ③ 哪些项是连续量程、哪些是整数档位尚未逐项实测，用"原样+去尾零"
    //    对两类都天然正确，不需要先分类。
    //
    // 实现：**按项分档 + 固定位数，不去尾零**（用户 2026-10-26 定案）——
    // 外倾角 / 前束角 3 位，其余 2 位。
    //   2 位：`55.3971` → `55.40`；`54.7019` → `54.70`；`50.0` → `50.00`
    //   3 位：`-0.0326` → `-0.033`；`0.0884` → `0.088`；`-2.8624` → `-2.862`
    //
    // ⚠️ **不要加去尾零**：那会让 `54.70` 变 `54.7`、`55.40` 变 `55.4`，
    //    同一列里位数参差不齐（用户 2026-10-26 报「刹车平衡变 1 位了」）。
    //    固定位数才能让数字列真正对齐。
    // ⚠️ 角度项用 3 位是因为量级小（-3.5 ~ 0.3 度）：2 位会把前束角
    //    `0.0326` 与 `0.0388` 压成 `0.03` / `0.04`，区分度损失 25%。
    //
    // ⚠️ 存储仍是原始 float（服务端 `REAL` / Kotlin `Float` 都不截断），
    //    只有**展示**按此分档。后续「应用调教」回写用原始值，不受影响。
    // ⚠️ 日志里的 `LAPsetup: aero=%.3f` 是**诊断打印格式**，与这里的展示精度
    //    无关——看到日志 3 位就以为精度只有 3 位是误读。
    fun fmt(v: Float?, digits: Int = 2): String =
        if (v == null) "缺失" else "%.${digits}f".format(v)
    val s = entry.setup

    // 单值项与双值项统一成同一个结构：左边项目名、右边 1~2 个值位。
    data class DetailItem(val label: String, val v1: String, val v2: String?)

    val items = listOf(
        DetailItem("赛道", entry.trackName, null),
        DetailItem("圈速", entry.lapDisplay, null),
        DetailItem("时间", entry.created, null),
        DetailItem("版本", entry.versionName, null),
        DetailItem("踏板模式", assistValue(entry.pedal), null),
        DetailItem("TC", assistValue(entry.tc), null),
        DetailItem("ABS", assistValue(entry.abs), null),
        DetailItem("车型", carLabel ?: "缺失", null),
        DetailItem("下压力", fmt(s?.frontWingAero), fmt(s?.rearWingAero)),
        DetailItem("底盘高度", fmt(s?.frontRideHeight), fmt(s?.rearRideHeight)),
        // 刹车平衡只有前值字段，后 = 100 − 前（游戏 UI 的 60/40 是派生量）。
        // ⚠️ 后值是**算出来的**，浮点减法可能带出尾差（100 − 58.709 = 41.291），
        //    由 fmt 的去尾零统一处理，不要额外取整。
        DetailItem(
            "刹车平衡",
            fmt(s?.brakeBias),
            s?.let { fmt(100f - it.brakeBias) } ?: "缺失",
        ),
        DetailItem("悬架刚度", fmt(s?.frontStiffness), fmt(s?.rearStiffness)),
        DetailItem("防倾杆", fmt(s?.frontRollbar), fmt(s?.rearRollbar)),
        DetailItem("外倾角", fmt(s?.frontCamber, 3), fmt(s?.rearCamber, 3)),
        DetailItem("前束角", fmt(s?.frontToe, 3), fmt(s?.rearToe, 3)),
    )

    // 双值项的值位宽度：**按本弹窗实际数据实测**，不猜基准串。
    //
    // ⚠️ 这里踩过三次坑，最终定案是「实测真实内容」而不是换布局策略：
    //   ① `width("-100.00")` 猜基准 ⇒ 单值项「赛道」「时间」被截断。
    //   ② 改 `%.4f` 后最宽值变 9 字符、基准没同步 ⇒ 双值项全被截成 `55.39...`。
    //   ③ 改 `weight(1f)` 平分 ⇒ 不截断了，但 v1 占半行、v2 被推到最右，
    //      两个数字隔得太远（2026-10-26 实机反馈）。
    //   ⇒ 正确做法：**items 是已知的 List，直接量出 v1/v2 的实际最宽值**。
    //      既不会截断（量的是真实内容），也不会过宽（不含"最坏情况"余量）。
    //      数据变化时自动跟着变（remember 的 key 含 items）。
    // 双值项的值位宽度：**只量双值项**（v2 != null 的行）的 v1/v2，不掺单值项。
    //
    // ⚠️ 这里踩过四次坑，每次都是"宽度基准"错：
    //   ① `width("-100.00")` 猜基准 ⇒ 单值项「赛道」「时间」被截断。
    //   ② 改 `%.4f` 后最宽值变 9 字符、基准没同步 ⇒ 双值项全被截成 `55.39...`。
    //   ③ 改 `weight(1f)` 平分 ⇒ 不截断但 v1 占半行、v2 被推到最右。
    //   ④ 量 `items` 全部值 ⇒ **单值项的长文本（赛道名/时间）把 v1 撑宽**，
    //      双值项两个数字隔得老远（2026-10-26 实机反馈）。
    //   ⇒ 正确做法：**只量双值项**。单值项走 `weight(1f)` 吃满剩余宽度，
    //      它的长度与双值项的列宽无关。
    val valueWidth = remember(measurer, density, valueStyle, items) {
        val widest = items.filter { it.v2 != null }
            .flatMap { listOfNotNull(it.v1, it.v2) }
            .maxByOrNull { measurer.measure(it, valueStyle, softWrap = false).size.width }
            ?: "0"
        with(density) { measurer.measure(widest, valueStyle, softWrap = false).size.width.toDp() }
    }

    OverlayDialog(
        show = show,
        // 点窗外/返回键 → 只翻 show（播退场动画），挂载由 onDismissFinished 卸。
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 标题居中粗体（项目铁律：标题居中、正文左对齐）。
                Text(
                    text = "圈速详情",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    // 标题也套 tnum（整个弹窗统一等宽，用户 2026-10-26 定案）。
                    style = MiuixTheme.textStyles.main.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    ).merge(DriverTabularDigits),
                    modifier = Modifier.fillMaxWidth(),
                )
                // 可滚动正文：15 行在小屏/大字号下会超过卡片 2/3 屏上限。
                // ⚠️ `weight(1f, fill = false)`：让正文成为"可让渡空间"的一方，
                //    超限时先收缩并滚动，而不是把底部按钮压扁（项目已踩过的坑）。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // 项目名：固定宽度 → 所有值列起始 x 恒等（左对齐一列）。
                            // 与值列之间留 40dp：24dp 时两列仍显拥挤，标签与值
                            // 读不出明显的分组分隔（2026-10-26 两次实机反馈，12→24→40）。
                            // ⚠️ 这个间距只影响「标签→值1」，值1→值2 是另一个
                            //     `Spacer(24.dp)`——两者刻意不等，标签侧要更宽。
                            Text(
                                text = item.label,
                                style = labelStyle,
                                color = colorScheme.onBackground.copy(alpha = 0.6f),
                                maxLines = 1,
                                modifier = Modifier.width(labelWidth + 40.dp),
                            )
                            if (item.v2 == null) {
                                // ── 单值项：吃满剩余宽度 ──
                                // ⚠️ **不要**给它 `width(valueWidth)`：那是双值项
                                // 单个值位的固定宽度（按 "-100.00" 量的），单值项的
                                // 内容（赛道名 / 时间）远宽于此，套上去会被截断
                                // （2026-10-26 实机：「2026-10-」只显示到日期一半）。
                                // 单值项没有"第二个值要对齐"的诉求，就该铺满。
                                Text(
                                    text = item.v1,
                                    style = valueStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                // ── 双值项：两个值位各用**实测最宽值**的宽度 ──
                                // 对齐由固定宽度保证；间距由 `Spacer(24.dp)` 控制，
                                // 不再被 weight 平分推远。
                                Text(
                                    text = item.v1,
                                    style = valueStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(valueWidth),
                                )
                                Spacer(Modifier.width(24.dp))
                                Text(
                                    text = item.v2,
                                    style = valueStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(valueWidth),
                                )
                            }
                        }
                    }
                }
                // 关闭按钮（蓝底，与项目其它弹窗一致）。
                TextButton(
                    text = "关闭",
                    // ⚠️ 与 onDismissRequest 同一语义：只翻 show 播退场动画。
                    onClick = onDismissRequest,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/**
 * 辅助配置的展示值：缺失维**显式显示「缺失」**（用户 2026-10-19 定案）。
 *
 * 服务端对缺失维返回的就是字面量 `"缺失"`（`token_label` 的空 token 分支），
 * 这里只需把空串也归一成同一个词——两套"缺失"写法会让同一张卡片出现
 * 空值和「缺失」两种表现。
 */
private fun assistValue(value: String): String =
    if (value.isEmpty()) "缺失" else value

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

/** 分页日志 tag：与 `LeaderboardScreen` 的 `TAG_BOARD` 分开，便于按页面过滤。 */
private const val TAG_DRIVER = "Driver"

// ── 底栏页签序号 ──
// ⚠️ 用常量而非裸 0/1/2：`tab` 同时出现在 LaunchedEffect key、when 分支、
//    底栏点击判定三处，裸数字写错一处就是"分页在概览页乱跑"这类隐蔽 bug。
private const val DRIVER_TAB_OVERVIEW = 0
private const val DRIVER_TAB_TIMETRIAL = 1
private const val DRIVER_TAB_GRAND_PRIX = 2

/** 底栏页数（pager 的 pageCount）。 */
private const val DRIVER_TAB_COUNT = 3

// ── 排序方式 ──
// 下标 = 对话框里 spinner 的序号；值 = 服务端 `sort` 参数（见 docs/design/PADDOCK_PLAN.md）。
// ⚠️ 默认 0 = 时间降序，与服务端缺省一致：两处若不一致，用户没动过排序也会
//    在首屏看到与服务端默认不同的次序（客户端传 time_desc 只是显式化了同一件事）。
private val SORT_LABELS = listOf("时间降序", "时间升序", "圈速降序", "圈速升序")
private val SORT_KEYS = listOf("time_desc", "time_asc", "lap_desc", "lap_asc")

/**
 * 车手主页底栏三项。
 *
 * 图标（2026-10-19 用户定案）：
 * - 计时赛 = 用户提供的 `Time Trial.svg`（秒表，`TimeTrialIcon`）
 * - 大奖赛 = **原计时赛用的奖杯图标**（`Icons.Rounded.EmojiEvents`）
 * - 概览 = 人像
 *
 * ⚠️ 图标是用户的决策域，改之前必须先问——本会话曾自作主张直接挑图标被纠正。
 */
private enum class DriverTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Overview("概览", Icons.Rounded.Person),
    Timetrial("计时赛", tools.alamobile.mod.ui.TimeTrialIcon),
    GrandPrix("大奖赛", Icons.Rounded.EmojiEvents),
}
