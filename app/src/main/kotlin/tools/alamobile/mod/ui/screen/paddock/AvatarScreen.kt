package tools.alamobile.mod.ui.screen.paddock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
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
import tools.alamobile.mod.ui.theme.LocalEnableBlur
import tools.alamobile.mod.ui.util.BlurredBar
import tools.alamobile.mod.ui.util.rememberBlurBackdrop
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
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.ByteArrayOutputStream

/**
 * 头像上传页（Route.Avatar）：注册后首次登录跳转，也可从个人卡再次进入。
 * 选图（system picker）→ CropImage 方形裁剪（1:1）→ 压缩 JPEG（≤2MB）→ 上传。
 * 成功后 Toast + 返回（pop）。
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

    // 选图 launcher：system photo picker → 进裁剪
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
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
    }

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
                                    onClick = { picker.launch(androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )) },
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
}