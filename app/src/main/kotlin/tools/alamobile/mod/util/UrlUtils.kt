package tools.alamobile.mod.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** 模块官方 QQ 交流群群号（OverviewPagerMiuix「QQ 群」入口与围场注册弹窗共用单源）。 */
const val MODULE_QQ_GROUP_CODE = "757940708"

/** 群卡片页 scheme 失败时的网页加群兜底链接。 */
const val MODULE_QQ_GROUP_FALLBACK_URL =
    "https://qun.qq.com/universal-share/share?ac=1&authKey=V0nuKHg0u%2BZKVi/jgDReAiZSCQdbMb0yMwaOSV49gejQWRtdz%2BG4G6eQQgWyFOJB&busi_data=eyJncm91cENvZGUiOiI3NTc5NDA3MDgiLCJ0b2tlbiI6IjVzRjZTTWpLckJIRExvRTk3K0QzVzVzJGK2N4QURRM2RwRjJWNkw0L29wcG9ocjI1NXo5T1hLZ2FJVkZXZkhlMVAiLCJ1aW4iOiIxMjU5OTc2NTIwIn0=&data=x1JvsLJUAovAdpfNmLQpuTN_-yGbUrMfCJ1VSQqD-QbIzj9-ZLiRKNEHNbJXpokkPhx5cc-RG47HyWYUrPBtTA&svctype=4&tempid=h5_group_info"

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