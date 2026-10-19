package tools.alamobile.mod.ui.screen.paddock

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tools.alamobile.mod.PaddockClient
import tools.alamobile.mod.ui.navigation3.LocalNavigator
import tools.alamobile.mod.ui.navigation3.Route
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 账号设置页（Route.AccountSettings，2026-10-20）：
 * 更换头像 / 修改用户名（各 30 天一次）+ 屏蔽缺失配置的圈速记录（一次性、不可撤销）。
 *
 * 入口 = 围场主页「围场指南」下方的独立卡。
 *
 * ## 三个动作的交互分工（用户定案）
 *
 * | 动作 | 冷却/不可逆 | 交互 |
 * |---|---|---|
 * | 更换头像 | 30 天 | 冷却中 Toast 剩余天数；可换则 push 头像页 |
 * | 修改用户名 | 30 天 | 冷却中 Toast；可改则弹输入框 |
 * | 屏蔽缺失记录 | 一次性、不可撤销 | 已屏蔽/无可屏蔽 → Toast；否则弹确认框 |
 *
 * ## 为什么状态全部从服务端拉
 *
 * 冷却剩余时间是**服务端时钟**算的（`/v1/me/account`），本机时间可被用户改。
 * 客户端只负责展示，不自行推算"上次操作 + 30 天"——那样改一下系统时间就能绕过。
 */
@Composable
fun AccountSettingsScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) androidx.compose.ui.graphics.Color.Transparent
    else colorScheme.surface
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<PaddockClient.AccountState?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 弹窗状态：mounted + show 双变量（miuix 规范：单变量会跳过退出动画）
    var showRename by remember { mutableStateOf(false) }
    var renameMounted by remember { mutableStateOf(false) }
    var showHideConfirm by remember { mutableStateOf(false) }
    var hideConfirmMounted by remember { mutableStateOf(false) }

    suspend fun reload() {
        val s = withContext(Dispatchers.IO) { PaddockClient.fetchAccountState() }
        state = s
        if (!s.ok && s.error.isNotEmpty()) {
            Toast.makeText(context, s.error, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "账号设置",
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
                        val s = state
                        val avatarRemain = s?.avatarCooldownSecs ?: 0
                        val nameRemain = s?.usernameCooldownSecs ?: 0

                        Card(modifier = Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = "更换头像",
                                summary = if (avatarRemain > 0) {
                                    "${daysText(avatarRemain)} 后可更换"
                                } else {
                                    "30 天内只能更换一次"
                                },
                                startAction = {
                                    Icon(
                                        Icons.Rounded.AccountCircle,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = {
                                    if (avatarRemain > 0) {
                                        Toast.makeText(
                                            context,
                                            "还需 ${daysText(avatarRemain)} 才能更换头像",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        navigator.push(Route.Avatar)
                                    }
                                },
                            )
                            ArrowPreference(
                                title = "修改用户名",
                                summary = if (nameRemain > 0) {
                                    "${daysText(nameRemain)} 后可修改"
                                } else {
                                    "30 天内只能修改一次"
                                },
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Badge,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = {
                                    if (nameRemain > 0) {
                                        Toast.makeText(
                                            context,
                                            "还需 ${daysText(nameRemain)} 才能修改用户名",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        showRename = true
                                        renameMounted = true
                                    }
                                },
                            )
                        }

                        // 屏蔽缺失配置的记录：单独一张卡（不可逆操作，与上面两项视觉隔离）
                        Card(modifier = Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = "屏蔽缺失配置的圈速记录",
                                summary = "仅在排行榜上屏蔽，记录不会删除",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.VisibilityOff,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = {
                                    val cur = state
                                    when {
                                        // 未拉到状态时不放行——否则可能绕过后端校验。
                                        cur == null -> Toast.makeText(
                                            context, "正在加载，请稍候", Toast.LENGTH_SHORT
                                        ).show()

                                        cur.hiddenMissing -> Toast.makeText(
                                            context,
                                            "您已经屏蔽过缺失配置的记录，该操作无法撤销！",
                                            Toast.LENGTH_LONG,
                                        ).show()

                                        !cur.hasHiddenCandidates -> Toast.makeText(
                                            context,
                                            "您没有可以屏蔽的圈速记录",
                                            Toast.LENGTH_SHORT,
                                        ).show()

                                        else -> {
                                            showHideConfirm = true
                                            hideConfirmMounted = true
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 修改用户名弹窗 ──
    if (renameMounted) {
        RenameDialog(
            show = showRename,
            busy = busy,
            onConfirm = { newName ->
                scope.launch {
                    busy = true
                    val err = withContext(Dispatchers.IO) { PaddockClient.changeUsername(newName) }
                    busy = false
                    if (err == null) {
                        showRename = false
                        Toast.makeText(context, "用户名已修改，下次登录请使用新用户名", Toast.LENGTH_LONG)
                            .show()
                        reload()
                    } else {
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { showRename = false },
            onDismissFinished = { renameMounted = false },
        )
    }

    // ── 屏蔽确认弹窗（不可撤销，要求逐字输入确认句）──
    if (hideConfirmMounted) {
        HideMissingConfirmDialog(
            show = showHideConfirm,
            busy = busy,
            onConfirm = {
                scope.launch {
                    busy = true
                    val err = withContext(Dispatchers.IO) { PaddockClient.hideMissingRecords() }
                    busy = false
                    showHideConfirm = false
                    if (err == null) {
                        Toast.makeText(context, "已屏蔽缺失配置的圈速记录", Toast.LENGTH_LONG).show()
                        reload()
                    } else {
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { showHideConfirm = false },
            onDismissFinished = { hideConfirmMounted = false },
        )
    }
}

/** 剩余秒数 → 「X 天」（向上取整：还剩 1 小时也显示「1 天」而非「0 天」）。 */
private fun daysText(secs: Long): String {
    if (secs <= 0) return "现在"
    val days = (secs + 86399) / 86400
    return "$days 天"
}

/**
 * 修改用户名弹窗：居中粗体标题 + 左对齐说明 + 输入框 + 左「取消」右「确认」。
 *
 * ⚠️ 输入框必须放正文区且给 `weight(1f, fill = false)`——miuix 的
 *    `DialogContentLayout` 对卡片有 2/3 屏硬上限，内容超限时会把无 weight 的
 *    按钮压成窄条（见 CLAUDE.md 弹窗排版红线）。
 */
@Composable
private fun RenameDialog(
    show: Boolean,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    OverlayDialog(
        show = show,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "修改用户名",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
                Text(
                    text = "用户名同时是登录名，修改后下次登录请使用新用户名。" +
                        "30 天内只能修改一次，且旧用户名会被释放。",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "新用户名",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        text = if (busy) "提交中…" else "确认",
                        onClick = { onConfirm(name.text.trim()) },
                        enabled = !busy && name.text.isNotBlank(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

/**
 * 屏蔽确认弹窗：不可撤销，要求用户**逐字输入**「我确认要屏蔽缺失配置的记录」。
 *
 * 为什么不是普通的"取消/确认"两键：该操作会永久改变榜单与积分，且无法回滚。
 * 打字确认把误触概率降到接近零，同时让用户真的读到后果（用户原话就是
 * 「让用户输入我确认要屏蔽缺失配置的记录，然后点确认才能屏蔽」）。
 */
@Composable
private fun HideMissingConfirmDialog(
    show: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    val phrase = "我确认要屏蔽缺失配置的记录"
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val matched = input.text.trim() == phrase
    OverlayDialog(
        show = show,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "屏蔽缺失配置的记录",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
                Text(
                    text = "这些记录将从排行榜与积分中移除，车手主页仍会显示并标注「已屏蔽」。\n\n" +
                        "⚠️ 该操作不可撤销，且无法恢复。\n\n" +
                        "请输入「$phrase」以确认：",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    label = "确认文本",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        text = if (busy) "处理中…" else "确认屏蔽",
                        onClick = onConfirm,
                        enabled = !busy && matched,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}
