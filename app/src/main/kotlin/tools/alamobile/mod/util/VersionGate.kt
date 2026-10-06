package tools.alamobile.mod.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/** 官版包名。 */
const val OFFICIAL_PKG = "com.Vince.AlamobileFormula"

/** 共存版（重打包）包名。 */
const val COEXISTENCE_PKG = "com.Takotsubo.AlamobileFormula"

/** 支持的游戏包名（官版 + 共存版）。 */
private val SUPPORTED_PACKAGES = setOf(OFFICIAL_PKG, COEXISTENCE_PKG)

const val SUPPORTED_VERSION_NAME = "8.0.6"
const val SUPPORTED_VERSION_CODE = 200150

/**
 * 共存版最低打包修订号（2026-10-06 引入）。
 *
 * ## 为什么要这个维度
 * 同版本期间对**共存版安装包**做改动时（如 NPatch 1.0.7→1.0.8、补 VIBRATE 权限），
 * `versionCode` **必须与官版保持一致**：它同时是 IL2CPP 偏移安全键（错版本装 hook
 * 即开屏闪退）和围场榜单分区键（服务端 `best_laps` 按 `version_code` 分赛季，官版/
 * 共存版必须同赛季）。⇒ 不能拿 versionCode 区分新旧共存包。
 *
 * 于是把"打包修订号"放到 `versionName` 后缀承载（官版不动）：
 * ```
 *   官版    versionName = "8.0.6"          （无后缀）
 *   共存版  versionName = "8.0.6 Fix 1"    （Fix N = 打包修订号）
 * ```
 * 旧共存包（无后缀 = Fix 0）在此判据下 `< MIN_COEX_FIX` → 判为不匹配 → 拦截。
 *
 * ## 两个维度正交
 * - **引擎维度** = [SUPPORTED_VERSION_NAME] + [SUPPORTED_VERSION_CODE]：管"偏移是否
 *   安全"。升到 8.0.7 时改这里，fix 后缀**重新从 0 起算**（新引擎按最新标准打包即可，
 *   无需后缀）。
 * - **打包维度** = Fix 后缀：管"是不是最新打包"。同引擎内发 Fix 2/Fix 3 自动放行
 *   （`fix >= MIN_COEX_FIX`），模块无需再改。
 *
 * ⚠️ 只有某个 Fix 是"必须升级"的（如修严重 bug）时才升 [MIN_COEX_FIX]。
 */
const val MIN_COEX_FIX = 1

// 8.0.4 (200146) 的 offsets 已不再匹配 8.0.6 的 libil2cpp.so。
// 若需同时支持两个版本，需要按版本号分发不同的 OffsetTable；
// 当前实现只支持一个版本（8.0.6），旧版本用户会收到 unsupported 警告。
// 围场版本榜保留 8.0.4 历史键（LeaderboardScreen VERSION_CODES）。

/** versionName 解析结果：`base` = 引擎版本（如 "8.0.6"），`fix` = 打包修订号（无后缀 = 0）。 */
data class ParsedGameVersion(val base: String, val fix: Int)

/** 匹配 `"8.0.6"` 或 `"8.0.6 Fix 1"`（Fix 大小写不敏感，数字可多位）。 */
private val COEX_VERSION_RE = Regex("""^(\S+)(?:\s+Fix\s+(\d+))?$""", RegexOption.IGNORE_CASE)

/**
 * 解析 versionName 为 (引擎版本, 打包修订号)。
 * `"8.0.6"` → `("8.0.6", 0)`；`"8.0.6 Fix 1"` → `("8.0.6", 1)`。格式不认识时返回 null。
 */
fun parseGameVersionName(versionName: String?): ParsedGameVersion? {
    val v = versionName?.trim() ?: return null
    if (v.isEmpty()) return null
    val m = COEX_VERSION_RE.matchEntire(v) ?: return null
    val base = m.groupValues[1]
    val fix = m.groupValues[2].toIntOrNull() ?: 0
    return ParsedGameVersion(base, fix)
}

/**
 * 版本判据（按包名分支，2026-10-06 拆维度）：
 * - **官版**：`versionName == "8.0.6"` 且 `versionCode == 200150`（精确相等，无后缀）。
 * - **共存版**：`versionCode == 200150` 且 `base == "8.0.6"` 且 `fix >= [MIN_COEX_FIX]`。
 *
 * ⚠️ 官版判据必须保持**精确相等**：官版 versionName 恒为 `"8.0.6"`，若放宽成"前缀/
 * base 匹配"会放行未来官版 8.0.6x 的误配（官版不做 Fix 后缀，只有共存版做）。
 */
fun isSupportedVersion(packageName: String, versionName: String?, versionCode: Long): Boolean {
    if (packageName !in SUPPORTED_PACKAGES) return false
    if (versionCode != SUPPORTED_VERSION_CODE.toLong()) return false
    return if (packageName == OFFICIAL_PKG) {
        versionName == SUPPORTED_VERSION_NAME
    } else {
        val gv = parseGameVersionName(versionName) ?: return false
        gv.base == SUPPORTED_VERSION_NAME && gv.fix >= MIN_COEX_FIX
    }
}

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
            isSupportedVersion(packageName, info.versionName, PackageInfoCompat.getLongVersionCode(info))
        }
    } catch (e: Throwable) {
        false
    }
}

/**
 * 供日志：返回本进程所装游戏的 `"versionName (versionCode)"`，读不到时返回 `"?"`。
 * 排查"旧共存版被拦截"时能直接看出用户装的是 `"8.0.6"`（旧）还是 `"8.0.6 Fix 1"`（新）。
 */
fun installedVersionDescription(context: Context?): String {
    if (context == null) return "?"
    return try {
        val packageName = context.packageName
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    } catch (e: Throwable) {
        "?"
    }
}
