package tools.alamobile.mod.ui.screen.paddock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * 头像裁剪结果压缩：读 URI → 两步降采样 → 双线性精修到 256 → JPEG q88。
 *
 * ## 为什么不在 cropper 里一步到位
 * `android-image-cropper` 的 `RESIZE_*` 系列内部走
 * `Bitmap.createScaledBitmap(..., filter = false)`，即**最近邻**。3072→256 是 12 倍
 * 降采样，最近邻会出明显锯齿/摩尔纹（实测 PSNR 比双线性低约 6dB）。
 * 所以 cropper 只负责把尺寸限到 1024 当内存护栏，真正的缩放质量在这一层保证。
 *
 * ## 为什么先算 inSampleSize 再解码
 * `BitmapFactory.decodeStream` 不解采样时，3072² 的图要吃 **37.7MB** 位图。
 * 分两步：`inJustDecodeBounds` 读尺寸 → `inSampleSize` 粗降到 ~512 → 再
 * `createScaledBitmap(..., filter = true)` 双线性精修。峰值内存降到 1/16 以内，
 * 且第二步的降幅小（512→256），双线性不会糊。
 *
 * ## 返回值
 * `Pair(字节, "image/jpeg")`；解码失败返回 null（调用方提示「图片读取失败」）。
 * 顺带修掉旧实现的 `openInputStream` 从不 close 的泄漏。
 */
internal fun compressAvatar(context: Context, uri: Uri): Pair<ByteArray, String>? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

    // 第一步：只读尺寸，不做完整解码
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // 粗降到「仍 >= 目标边长的 2 倍」，保证第二步是温和缩小而非放大
    val target = AVATAR_SIDE
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target * 2 &&
        bounds.outHeight / (sample * 2) >= target * 2
    ) {
        sample *= 2
    }

    // 第二步：按 sample 解码
    val coarse = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null

    // 第三步：双线性精修到目标边长（等比 + 居中裁方的语义由 cropper 已保证 1:1）
    val scaled = if (coarse.width == target && coarse.height == target) {
        coarse
    } else {
        Bitmap.createScaledBitmap(coarse, target, target, /* filter = */ true)
    }

    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, AVATAR_QUALITY, out)
    // 及时释放：大图在低端机上不主动回收会拖到下一轮 GC
    if (scaled !== coarse) scaled.recycle()
    if (coarse !== scaled) coarse.recycle()
    return out.toByteArray() to "image/jpeg"
}

/** 头像目标边长。与服务端迁移侧 `AVATAR_SIDE` 保持一致（改必须两边同改）。 */
private const val AVATAR_SIDE = 256

/** JPEG 质量。实测 256/q88 ≈ 22KB/张，比 q85 只大 2.3KB。 */
private const val AVATAR_QUALITY = 88
