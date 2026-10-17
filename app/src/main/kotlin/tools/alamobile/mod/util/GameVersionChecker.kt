package tools.alamobile.mod.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

// 适配的版本（与 VersionGate 中 SUPPORTED_VERSION_NAME / SUPPORTED_VERSION_CODE 一致）。
private const val ADAPTED_VERSION_NAME = SUPPORTED_VERSION_NAME
private const val ADAPTED_VERSION_CODE = SUPPORTED_VERSION_CODE.toLong()

// 包名常量（COEXISTENCE_PKG）统一定义在 VersionGate.kt，此处不再重复。

/**
 * 单个游戏的版本检测结果。
 *
 * 2026-10-18 起只有共存版一个目标包，官版已停止维护。
 */
sealed class GameVersionStatus {
    /** 已安装且版本与适配版本一致。 */
    data class Adapted(val versionName: String) : GameVersionStatus()

    /** 已安装但引擎版本（versionName base 或 versionCode）与适配版本不符。 */
    data class NotAdapted(val versionName: String) : GameVersionStatus()

    /**
     * 引擎版本正确，但**打包修订号不是当前适配的那一个**（`Fix N != SUPPORTED_COEX_FIX`）。
     * 与 [NotAdapted] 分开是为了让概览页胶囊给出更准确的提示（"需更新"而非
     * 笼统的"未适配"）——引擎没错、偏移是安全的，只是打包形态没验证过。
     */
    data class OutdatedBuild(val versionName: String) : GameVersionStatus()

    /** 未安装。 */
    data object NotInstalled : GameVersionStatus()
}

/**
 * 静默查询指定包名的安装版本，判断是否与适配版本一致。
 * 依赖 AndroidManifest <queries> 声明对应包名（API 30+ 包可见性）。
 *
 * 判据与 [isSupportedVersion] 同源，**逐项精确相等**：
 * versionCode 等于适配值 + versionName base 等于适配值 + fix == [SUPPORTED_COEX_FIX]。
 * 引擎版本不符 → [GameVersionStatus.NotAdapted]；引擎对但 fix 不等 →
 * [GameVersionStatus.OutdatedBuild]。
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
        val versionCode = PackageInfoCompat.getLongVersionCode(info)

        // 引擎版本（versionCode + versionName base）对，再看打包修订号。
        val gv = parseGameVersionName(versionName)
        if (gv == null ||
            versionCode != ADAPTED_VERSION_CODE ||
            gv.base != ADAPTED_VERSION_NAME
        ) {
            return GameVersionStatus.NotAdapted(versionName)
        }
        return if (gv.fix == SUPPORTED_COEX_FIX) {
            GameVersionStatus.Adapted(versionName)
        } else {
            GameVersionStatus.OutdatedBuild(versionName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        GameVersionStatus.NotInstalled
    } catch (_: Throwable) {
        GameVersionStatus.NotInstalled
    }
}
