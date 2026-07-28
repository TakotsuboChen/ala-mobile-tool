package tools.alamobile.mod.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

// Supported package names (original and coexistence versions)
private val SUPPORTED_PACKAGES = setOf(
    "com.Vince.AlamobileFormula",
    "com.Takotsubo.AlamobileFormula"
)

private const val SUPPORTED_VERSION_NAME = "8.0.0"
private const val SUPPORTED_VERSION_CODE = 200142

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
