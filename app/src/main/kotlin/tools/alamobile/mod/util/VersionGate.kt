package tools.alamobile.mod.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

private const val TARGET_PACKAGE = "com.Vince.AlamobileFormula"
private const val SUPPORTED_VERSION_NAME = "8.0.0"
private const val SUPPORTED_VERSION_CODE = 200142

fun isSupportedVersion(context: Context?): Boolean {
    if (context == null) return false

    return try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                TARGET_PACKAGE,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
        }
        val longVersionCode = info.longVersionCode
        info.versionName == SUPPORTED_VERSION_NAME &&
                longVersionCode == SUPPORTED_VERSION_CODE.toLong()
    } catch (e: Throwable) {
        false
    }
}

fun isSupportedVersion(packageName: String, versionName: String?, versionCode: Long): Boolean {
    return packageName == TARGET_PACKAGE &&
            versionName == SUPPORTED_VERSION_NAME &&
            versionCode == SUPPORTED_VERSION_CODE.toLong()
}
