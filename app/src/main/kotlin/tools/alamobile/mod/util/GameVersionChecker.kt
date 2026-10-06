package tools.alamobile.mod.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

// 适配的版本（与 VersionGate 中 SUPPORTED_VERSION_NAME / SUPPORTED_VERSION_CODE 一致）。
private const val ADAPTED_VERSION_NAME = SUPPORTED_VERSION_NAME
private const val ADAPTED_VERSION_CODE = SUPPORTED_VERSION_CODE.toLong()

// 包名常量（OFFICIAL_PKG / COEXISTENCE_PKG）统一定义在 VersionGate.kt，此处不再重复。

/**
 * 单个游戏的版本检测结果。
 */
sealed class GameVersionStatus {
    /** 已安装且版本与适配版本一致。 */
    data class Adapted(val versionName: String) : GameVersionStatus()

    /**
     * 已安装但版本与适配版本不符（含官版 versionCode/versionName 不符，或共存版
     * 打包修订号过旧 —— [OutdatedBuild]）。
     */
    data class NotAdapted(val versionName: String) : GameVersionStatus()

    /**
     * 共存版已安装、引擎版本正确，但**打包修订号过旧**（`Fix N < MIN_COEX_FIX`，
     * 或旧包无 Fix 后缀 = Fix 0）。与 [NotAdapted] 分开是为了让概览页胶囊给出
     * 更准确的提示（"旧版共存包，请更新"而非笼统的"未适配"）。
     */
    data class OutdatedBuild(val versionName: String) : GameVersionStatus()

    /** 未安装。 */
    data object NotInstalled : GameVersionStatus()
}

/**
 * 静默查询指定包名的安装版本，判断是否与适配版本一致。
 * 依赖 AndroidManifest <queries> 声明对应包名（API 30+ 包可见性）。
 *
 * 判据与 [isSupportedVersion] 同源（按包名分支）：
 * - 官版：versionName/versionCode 精确等于适配值。
 * - 共存版：versionCode 等于适配值 + versionName base 等于适配值 + fix >= MIN_COEX_FIX；
 *   fix 不足时返回 [GameVersionStatus.OutdatedBuild]。
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

        if (packageName == OFFICIAL_PKG) {
            if (versionName == ADAPTED_VERSION_NAME && versionCode == ADAPTED_VERSION_CODE) {
                GameVersionStatus.Adapted(versionName)
            } else {
                GameVersionStatus.NotAdapted(versionName)
            }
        } else {
            // 共存版：版本号/引擎版本对 + 打包修订号达到最低要求才算适配。
            val codeOk = versionCode == ADAPTED_VERSION_CODE
            val gv = parseGameVersionName(versionName)
            when {
                codeOk && gv != null && gv.base == ADAPTED_VERSION_NAME && gv.fix >= MIN_COEX_FIX ->
                    GameVersionStatus.Adapted(versionName)
                codeOk && gv != null && gv.base == ADAPTED_VERSION_NAME ->
                    GameVersionStatus.OutdatedBuild(versionName)
                else -> GameVersionStatus.NotAdapted(versionName)
            }
        }
    } catch (_: PackageManager.NameNotFoundException) {
        GameVersionStatus.NotInstalled
    } catch (_: Throwable) {
        GameVersionStatus.NotInstalled
    }
}
