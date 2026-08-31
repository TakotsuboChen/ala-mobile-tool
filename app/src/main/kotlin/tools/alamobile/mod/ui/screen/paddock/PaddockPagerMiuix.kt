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
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import tools.alamobile.mod.ui.navigation3.LocalNavigator
import tools.alamobile.mod.ui.navigation3.Route
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import tools.alamobile.mod.ui.viewmodel.PaddockViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 围场页（S3 最小版）：通行证核验（登录/注册）+ 已登录的个人信息卡。
 * 排行榜子页（积分榜/赛道榜）为下一步——此处先放 ArrowPreference 入口。
 * 所有 preference 组件走 miuix（KernelSU 全盘照搬红线），不手写 Row+Switch。
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
}

@Composable
private fun PaddockContent(
    uiState: tools.alamobile.mod.ui.viewmodel.PaddockViewModel.UiState,
    actions: PaddockViewModel,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    navigator: tools.alamobile.mod.ui.navigation3.Navigator,
) {
    if (uiState.loggedIn) {
        // ── 已登录：个人信息卡 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            top.yukonga.miuix.kmp.preference.ArrowPreference(
                title = uiState.username.ifBlank { "车手" },
                summary = "围场通行证已核验",
                startAction = {
                    Icon(Icons.Rounded.Person, modifier = Modifier.padding(end = 6.dp), contentDescription = null, tint = colorScheme.onBackground)
                },
                onClick = { },
            )
            top.yukonga.miuix.kmp.preference.ArrowPreference(
                title = "退出登录",
                summary = "清除本机登录态（成绩保留在服务器）",
                startAction = {
                    Icon(Icons.Rounded.Logout, modifier = Modifier.padding(end = 6.dp), contentDescription = null, tint = colorScheme.onBackground)
                },
                onClick = { actions.logout() },
            )
        }
    } else {
        // ── 未登录：通行证核验 ──
        LoginCard(uiState, actions)
        RegisterCard(uiState, actions, clipboard)
    }

    if (uiState.message.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            top.yukonga.miuix.kmp.basic.Text(
                text = uiState.message,
                modifier = Modifier.padding(12.dp),
                fontSize = 14.sp,
            )
        }
    }

    // ── 排行榜（登录后开放；本期占位，S3 后半接榜单页） ──
    Card(modifier = Modifier.fillMaxWidth()) {
        top.yukonga.miuix.kmp.preference.ArrowPreference(
            title = "圈速排行榜",
            summary = if (uiState.loggedIn) "查看积分榜与赛道榜" else "登录后可查看排行",
            enabled = uiState.loggedIn,
            startAction = {
                Icon(Icons.Rounded.Leaderboard, modifier = Modifier.padding(end = 6.dp), contentDescription = null, tint = colorScheme.onBackground)
            },
            onClick = { navigator.push(Route.Paddock) },
        )
    }
}

@Composable
private fun LoginCard(uiState: tools.alamobile.mod.ui.viewmodel.PaddockViewModel.UiState, actions: PaddockViewModel) {
    var loginName by remember { mutableStateOf(TextFieldValue(uiState.loginName)) }
    var loginPass by remember { mutableStateOf(TextFieldValue(uiState.loginPass)) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            top.yukonga.miuix.kmp.basic.Text("通行证核验", fontSize = 16.sp)
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
            )
            TextButton(
                text = if (uiState.loading) "核验中…" else "登录",
                onClick = { actions.login() },
                enabled = !uiState.loading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RegisterCard(
    uiState: tools.alamobile.mod.ui.viewmodel.PaddockViewModel.UiState,
    actions: PaddockViewModel,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
) {
    var regName by remember { mutableStateOf(TextFieldValue(uiState.regName)) }
    var regPass by remember { mutableStateOf(TextFieldValue(uiState.regPass)) }
    var regCodeInput by remember { mutableStateOf(TextFieldValue(uiState.regCodeInput)) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            top.yukonga.miuix.kmp.basic.Text("申请围场通行证", fontSize = 16.sp)
            TextField(
                value = regName,
                onValueChange = { regName = it; actions.setRegName(it.text) },
                label = "用户名（注册后不可改）",
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = regPass,
                onValueChange = { regPass = it; actions.setRegPass(it.text) },
                label = "密码（≥8位，数字+字母）",
                modifier = Modifier.fillMaxWidth(),
            )
            if (uiState.regCodeIssued.isEmpty()) {
                TextButton(
                    text = if (uiState.loading) "申请中…" else "① 注册（生成校验码）",
                    onClick = { actions.requestRegCode() },
                    enabled = !uiState.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // 校验码弹卡：复制 + 我已校验 两步
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        top.yukonga.miuix.kmp.basic.Text(
                            "请把下面这段话发到模块交流 QQ 群，CAMDA 助理回复\"校验成功\"后回来点\"我已校验\"：",
                            fontSize = 13.sp,
                        )
                        top.yukonga.miuix.kmp.basic.Text(
                            text = uiState.regCodeIssued,
                            fontSize = 15.sp,
                        )
                        TextButton(
                            text = "复制",
                            onClick = { clipboard.setText(AnnotatedString(uiState.regCodeIssued)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        top.yukonga.miuix.kmp.basic.Text("收到\"校验成功\"回复后：", fontSize = 13.sp)
                        TextField(
                            value = regCodeInput,
                            onValueChange = { regCodeInput = it; actions.setRegCodeInput(it.text) },
                            label = "粘贴校验码",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            text = if (uiState.loading) "核验中…" else "② 我已校验，完成注册",
                            onClick = { actions.verifyRegCode() },
                            enabled = !uiState.loading,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}