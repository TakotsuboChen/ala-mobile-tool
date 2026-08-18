package tools.alamobile.mod.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

fun openExternalUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 打开 QQ 群：优先直接拉起 QQ App，逐级降级到网页。
 *
 * 1. mqqapi 群卡片页（只需 groupCode，用户手动点申请加群）
 * 2. qun.qq.com 网页（浏览器打开后跳转 QQ）
 *
 * 注意：mqqopensdkapi 一键加群方案需要官方加群组件生成的 idkey，
 * universal-share URL 的 authKey 不是 idkey，用了会被 QQ 接住但解析失败。
 * scheme 是 QQ 未公开 API，可能随版本变动，多层降级保证可用性。
 */
fun openQqGroup(
    context: Context,
    groupCode: String,
    fallbackUrl: String,
) {
    // 1. 群卡片页（无 key，用户手动申请加群）
    val cardScheme = "mqqapi://card/show_pslcard?src_type=internal&version=1" +
        "&uin=$groupCode&card_type=group&source=external"
    if (tryStartActivity(context, cardScheme)) return

    // 2. 最终降级：网页链接
    openExternalUrl(context, fallbackUrl)
}

private fun tryStartActivity(context: Context, uri: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    true
} catch (_: Exception) {
    // ActivityNotFoundException = 没装 QQ 或该版本不支持此 scheme
    false
}