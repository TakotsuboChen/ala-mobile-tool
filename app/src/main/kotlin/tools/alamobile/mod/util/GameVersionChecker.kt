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
        if (gv.fix != SUPPORTED_COEX_FIX) {
            return GameVersionStatus.OutdatedBuild(versionName)
        }
        // 游戏包本身完全正确。NPatch 状态**不在这里判**——它是独立维度，
        // 由概览页的第二个胶囊单独显示（见 [checkNPatchManagerStatus]）。
        // 早期实现把 NPatch 判据塞进本函数，导致"游戏包胶囊变黄"却看不出
        // 是哪个组件有问题，且模块进程读不到框架信息（详见 [checkNPatchManagerStatus]）。
        return GameVersionStatus.Adapted(versionName)
    } catch (_: PackageManager.NameNotFoundException) {
        GameVersionStatus.NotInstalled
    } catch (_: Throwable) {
        GameVersionStatus.NotInstalled
    }
}

/**
 * NPatch 管理器（`top.nkbe.npatch`）的适配状态——概览页第二个胶囊的数据源。
 *
 * ## 为什么是独立维度
 *
 * 游戏包版本与 NPatch 版本**互不影响**：游戏包可以是 Fix 2 却配着旧 NPatch，
 * 也可以反过来。塞进同一个胶囊就丢失了"哪个组件有问题"这个关键信息。
 *
 * ## 判据只看管理器版本（用户 2026-10-25 定案）
 *
 * 模块进程是普通 App，永远不会被 Xposed 注入 ⇒ `frameworkName` /
 * `frameworkVersionCode` 恒读不到（那是进程级 property，只有游戏进程的
 * `onModuleLoaded` 写得进去）。详见 [evaluateManagerVersionGate] 注释。
 *
 * ⚠️ **已知局限**：管理器版本 ≠ 游戏包内框架版本时会显示"已适配"而游戏实际
 * 被拦（用户装了新管理器，但游戏包 `assets/npatch/config.json` 里的框架还是
 * 旧的）。游戏内的循环 Toast 才是权威提示。
 */
fun checkNPatchManagerStatus(context: Context): NPatchManagerStatus =
    when (val verdict = evaluateManagerVersionGate(context)) {
        is ManagerVerdict.Mismatch -> NPatchManagerStatus.Outdated(verdict.managerCode)
        ManagerVerdict.Missing -> NPatchManagerStatus.Missing
        ManagerVerdict.Pass -> NPatchManagerStatus.Adapted(SUPPORTED_NPATCH_VERSION_CODE)
    }

/** NPatch 管理器适配状态（概览页胶囊用）。 */
sealed class NPatchManagerStatus {
    /** 管理器版本与 [SUPPORTED_NPATCH_VERSION_CODE] 一致。 */
    data class Adapted(val versionCode: Long) : NPatchManagerStatus()

    /** 版本不符。[versionCode] 为实际值。 */
    data class Outdated(val versionCode: Long) : NPatchManagerStatus()

    /** 未安装。 */
    data object Missing : NPatchManagerStatus()
}
