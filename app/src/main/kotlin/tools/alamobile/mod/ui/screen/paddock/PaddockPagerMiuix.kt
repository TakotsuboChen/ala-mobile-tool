package tools.alamobile.mod.ui.screen.paddock

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tools.alamobile.mod.PaddockClient
import tools.alamobile.mod.ui.navigation3.LocalNavigator
import tools.alamobile.mod.ui.navigation3.Route
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.ui.viewmodel.PaddockViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 围场页：未登录只显示"通行证核验"（登录/注册并排）；
 * 登录后显示个人信息卡 + 圈速排行榜入口。
 *
 * 注册流（2026-09-01 重设计）：输入用户名+密码 → 点"注册" → 服务端生成申请指令 →
 * 弹窗展示"申请围场通行证#码"（点击复制指令即关弹窗）→ 用户去 CAMDA 群发送 →
 * bot 校验成功即建号（回复车手 ID）→ 用户回模块用同一账号密码直接登录。
 *
 * 提示出口全部走 Toast（uiState.message 卡片已删）；注册弹窗走 OverlayDialog
 * 常驻组合树模式（show 翻 false 触发退出动画，onDismissFinished 清理）。
 */
@Composable
fun PaddockPagerMiuix(
    uiState: tools.alamobile.mod.ui.viewmodel.PaddockViewModel.UiState,
    actions: PaddockViewModel,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val clipboard = LocalClipboardManager.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current

    // 一次性提示事件 → Toast
    LaunchedEffect(Unit) {
        actions.toast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "围场",
                    color = barColor,
                    scrollBehavior = scrollBehavior,
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
                        PaddockContent(uiState, actions, clipboard, navigator)
                    }
                }
            }
        }
    }

    // 注册弹窗：常驻组合树（show 驱动），点按钮/外点都走 dismiss → 退出动画后清理
    if (uiState.regDialogCommand.isNotEmpty()) {
        RegisterDialog(
            command = uiState.regDialogCommand,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
            onDismissFinished = { actions.dismissRegDialog() },
        )
    }
}

@Composable
private fun PaddockContent(
    uiState: tools.alamobile.mod.ui.viewmodel.PaddockViewModel.UiState,
    actions: PaddockViewModel,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    navigator: tools.alamobile.mod.ui.navigation3.Navigator,
) {
    // 退出登录确认弹窗（常驻组合树，show 驱动）——声明在函数级，与 loggedIn 分支解耦
    var showLogoutDialog by remember { mutableStateOf(false) }
    var logoutDialogMounted by remember { mutableStateOf(false) }

    if (uiState.loggedIn) {
        // ── 已登录：用户信息连体卡 + 功能入口 ──
        // 头像：登录后有 userId 时异步拉取（无头像/失败保持 Person 图标）
        var avatar by remember(uiState.userId) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(uiState.userId) {
            if (uiState.userId.isNotEmpty()) {
                avatar = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    PaddockClient.fetchAvatar("/v1/avatar/${uiState.userId}")?.let { bytes ->
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            }
        }

        // 用户信息连体卡：首行（头像+粗体用户名）较高，下两行仿设备信息行高
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
                        androidx.compose.foundation.Image(
                            bitmap = avatar!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape),
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
                    Column {
                        Text(
                            text = uiState.username.ifBlank { "车手" },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (uiState.regSeq > 0) {
                            Text(
                                text = "车手 #${uiState.regSeq}",
                                fontSize = 13.sp,
                                color = colorScheme.onBackground.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
                // 三列统计：标题行 + 值行（行高仿概览页设备信息 15sp）
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatCell("赛季积分", Modifier.weight(1f))
                    StatCell("赛季胜场", Modifier.weight(1f))
                    StatCell("计时赛积分", Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatValue("暂无", Modifier.weight(1f))
                    StatValue("暂无", Modifier.weight(1f))
                    StatValue(if (uiState.totalPoints > 0) "${uiState.totalPoints}" else "暂无", Modifier.weight(1f))
                }
            }
        }

        // 计时赛排行榜（独立卡）+ 大奖赛/娱乐匹配（连体卡）
        Card(modifier = Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = "计时赛排行榜",
                startAction = {
                    Icon(Icons.Rounded.Leaderboard, modifier = Modifier.padding(end = 6.dp), contentDescription = null, tint = colorScheme.onBackground)
                },
                onClick = { navigator.push(Route.Paddock) },
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = "大奖赛",
                summary = "争夺积分，成为赛季车手冠军",
                startAction = {
                    Icon(Icons.Rounded.EmojiEvents, modifier = Modifier.padding(end = 6.dp), contentDescription = null, tint = colorScheme.onBackground)
                },
                onClick = { actions.toastDev() },
            )
            ArrowPreference(
                title = "娱乐匹配",
                summary = "短程正赛，享受赛道攻防",
                startAction = {
                    Icon(Icons.Rounded.SportsEsports, modifier = Modifier.padding(end = 6.dp), contentDescription = null, tint = colorScheme.onBackground)
                },
                onClick = { actions.toastDev() },
            )
        }

        // 退出登录：整页最下的独立卡，点击弹确认（防误触）
        Card(modifier = Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = "退出登录",
                startAction = {
                    Icon(Icons.Rounded.Logout, modifier = Modifier.padding(end = 6.dp), contentDescription = null, tint = colorScheme.onBackground)
                },
                onClick = { showLogoutDialog = true },
            )
        }
    } else {
        // ── 未登录：通行证核验（唯一一块）──
        SmallTitle(
            text = "通行证核验",
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        )
        VerifyCard(uiState, actions)
    }

    // 退出登录确认弹窗：常驻组合树（show 驱动），确认才真正登出
    if (showLogoutDialog || logoutDialogMounted) {
        logoutDialogMounted = true
        LogoutConfirmDialog(
            show = showLogoutDialog,
            onConfirm = {
                showLogoutDialog = false
                actions.logout()
            },
            onDismiss = { showLogoutDialog = false },
            onDismissFinished = { logoutDialogMounted = false },
        )
    }
}

/** 退出登录确认弹窗：左"取消"右蓝色"退出"。 */
@Composable
private fun LogoutConfirmDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    OverlayDialog(
        show = show,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "确定要退出登录吗？",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        text = "退出",
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

/** 统计列标题（第二行）。 */
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

/** 统计列值（第三行）。 */
@Composable
private fun StatValue(value: String, modifier: Modifier = Modifier) {
    Text(
        text = value,
        fontSize = 15.sp,
        modifier = modifier,
        textAlign = TextAlign.Center,
    )
}

/** 登录/注册并排表单：用户名+密码共用，左"注册"右蓝色"登录"。 */
@Composable
private fun VerifyCard(uiState: tools.alamobile.mod.ui.viewmodel.PaddockViewModel.UiState, actions: PaddockViewModel) {
    var loginName by remember { mutableStateOf(TextFieldValue(uiState.loginName)) }
    var loginPass by remember { mutableStateOf(TextFieldValue(uiState.loginPass)) }
    var passVisible by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = loginName,
                onValueChange = { loginName = it; actions.setLoginName(it.text) },
                label = "用户名",
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = loginPass,
                onValueChange = { loginPass = it; actions.setLoginPass(it.text) },
                label = "密码",
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    // 显隐切换图标：IconButton 无背景，避免抢 TextField 尾部空间
                    IconButton(onClick = { passVisible = !passVisible }) {
                        Icon(
                            imageVector = if (passVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (passVisible) "隐藏密码" else "显示密码",
                            tint = colorScheme.onBackground.copy(alpha = 0.6f),
                        )
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = if (uiState.loading && uiState.regDialogCommand.isEmpty()) "处理中…" else "注册",
                    onClick = { actions.register() },
                    enabled = !uiState.loading,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (uiState.loading && uiState.regDialogCommand.isNotEmpty()) "登录中…" else "登录",
                    onClick = { actions.login() },
                    enabled = !uiState.loading,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                text = "忘记密码？",
                onClick = { actions.setShowReset(true) },
                enabled = !uiState.loading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 重置密码弹窗：常驻组合树（show 驱动），提交成功后由 ViewModel 置 showReset=false 关闭
    var resetDialogMounted by remember { mutableStateOf(false) }
    if (uiState.showReset || resetDialogMounted) {
        resetDialogMounted = true
        ResetPasswordDialog(
            uiState = uiState,
            actions = actions,
            onDismiss = { actions.setShowReset(false) },
            onDismissFinished = { resetDialogMounted = false },
        )
    }
}

/**
 * 重置密码弹窗：居中粗体标题"重置密码" + 说明正文 + 重置码/新密码两输入框
 * （新密码不标条件，格式错误走 Toast），左灰"取消"右蓝"提交重置"。
 */
@Composable
private fun ResetPasswordDialog(
    uiState: tools.alamobile.mod.ui.viewmodel.PaddockViewModel.UiState,
    actions: PaddockViewModel,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    var resetCode by remember { mutableStateOf(TextFieldValue(uiState.resetCode)) }
    var resetPass by remember { mutableStateOf(TextFieldValue(uiState.resetPass)) }
    var passVisible by remember { mutableStateOf(false) }
    OverlayDialog(
        show = uiState.showReset,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "重置密码",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "在模块交流 QQ 群发送「我需要重置密码」，助理按你的群身份自动匹配账号并回复重置码，回填此处：",
                    fontSize = 14.sp,
                )
                TextField(
                    value = resetCode,
                    onValueChange = { resetCode = it; actions.setResetCode(it.text) },
                    label = "重置码",
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = resetPass,
                    onValueChange = { resetPass = it; actions.setResetPass(it.text) },
                    label = "新密码",
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passVisible = !passVisible }) {
                            Icon(
                                imageVector = if (passVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (passVisible) "隐藏密码" else "显示密码",
                                tint = colorScheme.onBackground.copy(alpha = 0.6f),
                            )
                        }
                    },
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        enabled = !uiState.loading,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    TextButton(
                        text = if (uiState.loading) "提交中…" else "提交重置",
                        onClick = { actions.submitReset() },
                        enabled = !uiState.loading,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

/**
 * 注册申请弹窗：居中标题"申请围场通行证"，左对齐正文三行，
 * 唯一蓝色按钮"点击复制指令"（复制后关弹窗）。
 */
@Composable
private fun RegisterDialog(
    command: String,
    onCopy: (String) -> Unit,
    onDismissFinished: () -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    OverlayDialog(
        show = show,
        onDismissRequest = { show = false },
        onDismissFinished = { onDismissFinished() },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "申请围场通行证",
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "请复制以下申请指令，直接发送到交流群内：",
                        fontSize = 14.sp,
                    )
                    Text(
                        text = command,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "智能助理回复校验成功后返回此处点击登录即可。",
                        fontSize = 14.sp,
                    )
                }
                TextButton(
                    text = "点击复制指令",
                    onClick = {
                        onCopy(command)
                        show = false
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}