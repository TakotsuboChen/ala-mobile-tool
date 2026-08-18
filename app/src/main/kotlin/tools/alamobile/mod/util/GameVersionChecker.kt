package tools.alamobile.mod.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

// 官版与共存版包名。与 VersionGate.SUPPORTED_PACKAGES 保持一致。
const val OFFICIAL_PKG = "com.Vince.AlamobileFormula"
const val COEXISTENCE_PKG = "com.Takotsubo.AlamobileFormula"

// 适配的版本（与 VersionGate 中 SUPPORTED_VERSION_NAME / SUPPORTED_VERSION_CODE 一致）。
private const val ADAPTED_VERSION_NAME = "8.0.4"
private const val ADAPTED_VERSION_CODE = 200146L

/**
 * 单个游戏的版本检测结果。
 */
sealed class GameVersionStatus {
    /** 已安装且版本与适配版本一致。 */
    data class Adapted(val versionName: String) : GameVersionStatus()
    /** 已安装但版本与适配版本不符。 */
    data class NotAdapted(val versionName: String) : GameVersionStatus()
    /** 未安装。 */
    data object NotInstalled : GameVersionStatus()
}

/**
 * 静默查询指定包名的安装版本，判断是否与适配版本一致。
 * 依赖 AndroidManifest <queries> 声明对应包名（API 30+ 包可见性）。
 */
fun checkGameVersion(context: Context, packageName: String): GameVersionStatus {
    return try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        val versionName = info.versionName ?: ""
        if (versionName == ADAPTED_VERSION_NAME && PackageInfoCompat.getLongVersionCode(info) == ADAPTED_VERSION_CODE) {
            GameVersionStatus.Adapted(versionName)
        } else {
            GameVersionStatus.NotAdapted(versionName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        GameVersionStatus.NotInstalled
    } catch (_: Throwable) {
        GameVersionStatus.NotInstalled
    }
}