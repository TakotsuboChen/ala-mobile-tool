package tools.alamobile.mod.ui.screen.paddock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tools.alamobile.mod.PaddockClient
import tools.alamobile.mod.util.Logger
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.ByteArrayOutputStream

/**
 * 选图契约：**定向启动用户选中的 App**（component 由自绘选择器决定）。
 *
 * ⚠️ 为什么不用系统 chooser（2026-10-20 三次实机证伪，Flyme 20）：
 * 1. `createChooser(裸 GET_CONTENT)` → 候选只有 Google Photos → **单目标直通**，看不到列表；
 * 2. 基底换 `OPEN_DOCUMENT` → 同样单目标直通到文件管理器；
 * 3. 两次 `EXTRA_INITIAL_INTENTS` 补候选**都被 Flyme 定制 ChooserActivity 忽略**。
 * ⇒ 系统 chooser 是否画列表、是否尊重 initial intents 全由 OEM 决定，应用侧无法保证。
 *
 * 现在的做法：自己 `queryIntentActivities` 汇总候选（GET_CONTENT + OPEN_DOCUMENT 两个 action，
 * 按 package 去重），自绘弹窗让用户点选，再用 `setComponent` 定向启动——列表内容完全可控。
 *
 * 依赖 manifest `<queries>` 的两个 intent 声明：Android 11+ 不声明时查询恒返回空。
 */
private class ChooserImageContract(
    /** 在 launch 时读取目标 App——契约实例必须稳定，目标由外部 state 决定。 */
    private val target: () -> ImagePickerApp?,
) : ActivityResultContract<String, android.net.Uri?>() {
    override fun createIntent(context: android.content.Context, input: String): android.content.Intent {
        val app = target() ?: error("未选择目标 App")
        return android.content.Intent(app.action)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType(input)
            .setComponent(app.component)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): android.net.Uri? {
        if (resultCode != android.app.Activity.RESULT_OK) return null
        return intent?.data
    }
}

/** 一个可选的图片选择 App（自绘选择器的一行）。 */
private data class ImagePickerApp(
    /** **应用名称**（ApplicationInfo label），不是 activity label。 */
    val label: String,
    val icon: android.graphics.Bitmap?,
    val component: android.content.ComponentName,
    /** 该 App 注册的 action，决定启动时用哪个（GET_CONTENT 与 OPEN_DOCUMENT 语义不同）。 */
    val action: String,
)

/**
 * Drawable → Bitmap（Compose `Image` 需要位图；项目未引 core-ktx 的 `toBitmap()`）。
 * 按固有尺寸绘制，无固有尺寸时回落 96px。
 */
private fun android.graphics.drawable.Drawable.toBitmapCompat(): android.graphics.Bitmap {
    val w = intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = intrinsicHeight.takeIf { it > 0 } ?: 96
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

/**
 * 汇总所有能选图片的 App：`GET_CONTENT`（相册类）+ `OPEN_DOCUMENT`（文件管理器类），
 * 按 packageName 去重（同一 App 可能两个 action 都注册，优先 GET_CONTENT）。
 *
 * ⚠️ 名称/图标必须取 **ApplicationInfo** 而不是 `ResolveInfo.loadLabel()`：
 * 后者返回的是最具体的 activity label——谷歌相册的选择器活动叫「媒体选择工具」、
 * MT 管理器的活动叫「选择」，用户认不出是哪个 App（2026-10-20 实机反馈）。
 */
private fun collectImagePickers(context: android.content.Context, mime: String): List<ImagePickerApp> {
    val pm = context.packageManager
    val byPackage = LinkedHashMap<String, ImagePickerApp>()
    val actions = listOf(android.content.Intent.ACTION_GET_CONTENT, android.content.Intent.ACTION_OPEN_DOCUMENT)
    for (action in actions) {
        val intent = android.content.Intent(action)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType(mime)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (e: Throwable) {
            Logger.w("AvatarScreen", "queryIntentActivities($action) 失败: ${e.message}")
            emptyList()
        }
        for (info in resolved) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName) continue
            byPackage.getOrPut(pkg) {
                // 应用级 label / icon：用户能认出的「应用名称」，不是活动名。
                val appInfo = try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (_: Throwable) {
                    null
                }
                val label = appInfo?.loadLabel(pm)?.toString()?.ifBlank { null }
                    ?: info.loadLabel(pm).toString().ifBlank { pkg }
                val icon = try {
                    appInfo?.loadIcon(pm)?.toBitmapCompat()
                } catch (e: Throwable) {
                    Logger.w("AvatarScreen", "loadIcon($pkg) 失败: ${e.message}")
                    null
                }
                ImagePickerApp(
                    label = label,
                    icon = icon,
                    component = android.content.ComponentName(pkg, info.activityInfo.name),
                    action = action,
                )
            }
        }
    }
    val list = byPackage.values.sortedBy { it.label.lowercase() }
    Logger.i("AvatarScreen", "图片选择候选 ${list.size} 个: ${list.joinToString { "${it.label}(${it.action.substringAfterLast('.')})" }}")
    return list
}

/**
 * 头像上传页（Route.Avatar）：注册后首次登录跳转，也可从个人卡再次进入。
 * 选图（ACTION_GET_CONTENT + 应用选择器）→ CropImage 方形裁剪（1:1）→ 压缩 JPEG（≤2MB）→ 上传。
 * 成功后 Toast + 返回（pop）。
 *
 * ⚠️ 选图契约的两条红线：
 * 1. 不能用 PickVisualMedia——API < 33 依赖 Google 系统照片选择器组件，无 GMS 设备上打不开（用户反馈）。
 * 2. 不能裸发 GetContent——设备设过默认相册时直接进那个 App，用户没机会选「文件管理器还是相册」。
 *    必须 createChooser 把候选集（相册 / 文件管理器 / DocumentsUI）显式列出来。
 */
@Composable
fun AvatarScreen(
    onUploaded: () -> Unit = { },
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val context = LocalContext.current
    var uploading by remember { mutableStateOf(false) }
    var uploaded by remember { mutableStateOf(false) }

    // 上传工作流：裁剪完成 → IO 线程压缩+上传 → Toast + 返回
    suspend fun upload(bytes: ByteArray, contentType: String) {
        uploading = true
        val err = withContext(Dispatchers.IO) {
            PaddockClient.uploadAvatar(bytes, contentType)
        }
        uploading = false
        if (err == null) {
            uploaded = true
            Toast.makeText(context, "头像已更新", Toast.LENGTH_SHORT).show()
            onUploaded()
        } else {
            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
        }
    }

    // 裁剪 launcher（vanniktech CropImageContract，options 定 1:1 方形）
    val cropper = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val uri = result.uriContent
            if (uri != null) {
                MainScope().launch {
                    val packed = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                        val bmp = BitmapFactory.decodeStream(input)
                        if (bmp == null) return@withContext null
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        Pair(out.toByteArray(), "image/jpeg")
                    }
                    if (packed != null) upload(packed.first, packed.second)
                    else Toast.makeText(context, "图片读取失败", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(context, "已取消裁剪", Toast.LENGTH_SHORT).show()
        }
    }

    // 选图统一处理：拿到 URI 后进 1:1 裁剪
    fun handlePicked(uri: android.net.Uri?) {
        if (uri == null) return
        cropper.launch(
            CropImageContractOptions(
                uri = uri,
                cropImageOptions = CropImageOptions(
                    aspectRatioX = 1,
                    aspectRatioY = 1,
                    fixAspectRatio = true,
                    outputCompressFormat = Bitmap.CompressFormat.JPEG,
                    cropShape = CropImageView.CropShape.OVAL,
                    guidelines = CropImageView.Guidelines.ON,
                ),
            )
        )
    }

    // 选图：自绘应用选择器（见 collectImagePickers 注释——系统 chooser 在 Flyme 上不可控）。
    // 目标 App 存在 state 里，契约在 launch 时读取；launcher 本身保持稳定。
    val selectedPicker = remember { mutableStateOf<ImagePickerApp?>(null) }
    var pickerApps by remember { mutableStateOf<List<ImagePickerApp>>(emptyList()) }
    var showPickerDialog by remember { mutableStateOf(false) }
    var pickerDialogMounted by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        ChooserImageContract { selectedPicker.value }
    ) { handlePicked(it) }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "上传头像",
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
                        SmallTitle(
                            text = "车手头像",
                            insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("🖼️", fontSize = 40.sp)
                                }
                                Text(
                                    "选择一张图片，裁剪为方形后上传到围场服务器。",
                                    fontSize = 14.sp,
                                    color = colorScheme.onBackground.copy(alpha = 0.6f),
                                )
                                TextButton(
                                    text = if (uploading) "上传中…" else if (uploaded) "再换一张" else "选择图片",
                                    onClick = {
                                        // 现查候选（用户可能刚装了相册 App），再弹自绘选择器。
                                        pickerApps = collectImagePickers(context, "image/*")
                                        if (pickerApps.isEmpty()) {
                                            Toast.makeText(
                                                context, "设备上没有可用的图片选择器", Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            showPickerDialog = true
                                            pickerDialogMounted = true
                                        }
                                    },
                                    enabled = !uploading,
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 自绘应用选择器：列出所有能选图片的 App，点哪个就定向启动哪个。
    // 用 mounted + show 双变量驱动（miuix 规范：单变量会跳过退出动画）。
    if (pickerDialogMounted) {
        OverlayDialog(
            show = showPickerDialog,
            onDismissRequest = { showPickerDialog = false },
            onDismissFinished = { pickerDialogMounted = false },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "选择图片",
                        fontSize = 17.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                    // 候选可能很多，weight(1f, fill = false) 让列表在超过 2/3 屏上限时先收缩滚动，
                    // 而不是把下方「取消」按钮压扁（miuix DialogContentLayout 硬上限，见 CLAUDE.md）。
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        pickerApps.forEach { app ->
                            BasicComponent(
                                title = app.label,
                                insideMargin = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                                startAction = {
                                    val icon = app.icon
                                    if (icon != null) {
                                        Image(
                                            bitmap = icon.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(end = 12.dp)
                                                .size(32.dp),
                                            contentScale = ContentScale.Fit,
                                        )
                                    } else {
                                        // 拿不到图标时留同宽占位，保持各行标题左缘对齐。
                                        androidx.compose.foundation.layout.Spacer(
                                            Modifier
                                                .size(width = 32.dp, height = 1.dp)
                                                .padding(end = 12.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    selectedPicker.value = app
                                    showPickerDialog = false
                                    try {
                                        picker.launch("image/*")
                                    } catch (e: Throwable) {
                                        Logger.w("AvatarScreen", "启动 ${app.label} 失败: ${e.message}")
                                        Toast.makeText(context, "无法启动 ${app.label}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        }
                    }
                    TextButton(
                        text = "取消",
                        onClick = { showPickerDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}