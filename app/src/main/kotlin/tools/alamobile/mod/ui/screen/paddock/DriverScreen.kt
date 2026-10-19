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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.alamobile.mod.PaddockClient
import tools.alamobile.mod.ui.navigation3.LocalNavigator
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.util.Logger
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
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
                    "load more: offset=$offset total=$total " +
                        "track=$selectedTrack ver=$selectedVersion zero=$zeroOnly best=$bestOnly",
                )
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
                        onTrack = { selectedTrack = it },
                        onVersion = { selectedVersion = it },
                        onZeroOnly = { zeroOnly = it },
                        onBestOnly = { bestOnly = it },
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
                //    `docs/zh_CN/components/smalltitle.md` 的组合示例与 KernelSU 的
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
 * 计时赛页：四维筛选卡（固定在滚动区外）+ 全量圈速记录（分页）。
 *
 * 筛选卡钉在滚动区之外的理由与排行榜页相同：下拉刷新/滚动时筛选控件不随内容移动。
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
    onTrack: (Int) -> Unit,
    onVersion: (Int) -> Unit,
    onZeroOnly: (Boolean) -> Unit,
    onBestOnly: (Boolean) -> Unit,
) {
    Column {
        // ── 筛选卡（固定在滚动区之外，同排行榜页的筛选卡位置）──
        Column(
            modifier = Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .padding(horizontal = 12.dp),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    OverlaySpinnerPreference(
                        items = remember {
                            listOf(DropdownItem(text = "全部")) +
                                TRACK_NAMES.map { DropdownItem(text = it) }
                        },
                        selectedIndex = selectedTrack,
                        title = "赛道",
                        onSelectedIndexChange = onTrack,
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
                        onSelectedIndexChange = onVersion,
                    )
                    SwitchPreference(
                        checked = zeroOnly,
                        onCheckedChange = onZeroOnly,
                        title = "仅零辅助",
                        summary = "TC 与 ABS 均为关",
                    )
                    SwitchPreference(
                        checked = bestOnly,
                        onCheckedChange = onBestOnly,
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
