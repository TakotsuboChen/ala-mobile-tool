package tools.alamobile.mod.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

// Supported package names (original and coexistence versions)
private val SUPPORTED_PACKAGES = setOf(
    "com.Vince.AlamobileFormula",
    "com.Takotsubo.AlamobileFormula"
)

const val SUPPORTED_VERSION_NAME = "8.0.6"
const val SUPPORTED_VERSION_CODE = 200150

// 8.0.4 (200146) 的 offsets 已不再匹配 8.0.6 的 libil2cpp.so。
// 若需同时支持两个版本，需要按版本号分发不同的 OffsetTable；
// 当前实现只支持一个版本（8.0.6），旧版本用户会收到 unsupported 警告。
// 围场版本榜保留 8.0.4 历史键（LeaderboardScreen VERSION_CODES）。

fun isSupportedVersion(context: Context?): Boolean {
    if (context == null) return false

    return try {
        val packageName = context.packageName
        if (packageName !in SUPPORTED_PACKAGES) {
            false
        } else {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            val longVersionCode = info.longVersionCode
            info.versionName == SUPPORTED_VERSION_NAME &&
                    longVersionCode == SUPPORTED_VERSION_CODE.toLong()
        }
    } catch (e: Throwable) {
        false
    }
}

fun isSupportedVersion(packageName: String, versionName: String?, versionCode: Long): Boolean {
    return packageName in SUPPORTED_PACKAGES &&
            versionName == SUPPORTED_VERSION_NAME &&
            versionCode == SUPPORTED_VERSION_CODE.toLong()
}
