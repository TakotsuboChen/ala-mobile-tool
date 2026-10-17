package tools.alamobile.mod.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/**
 * 共存版（重打包）包名——**唯一支持的游戏包**。
 *
 * ⚠️ 官版 `com.Vince.AlamobileFormula` 已于 2026-10-18 **彻底停止维护**：官版
 * APK 没有 VIBRATE 权限（抓地力反馈只能走 `Fallback` 叩击）、且用户实际装的是
 * NPatch 共存版（非 root 为绝对多数）。继续留官版分支只会让每个包名判断多一条
 * 永不验证的死路径。官版用户已被 `VersionGate` 拒绝（非本包名 ⇒ 零 hook）。
 */
const val COEXISTENCE_PKG = "com.Takotsubo.AlamobileFormula"

/** 支持的游戏包名（仅共存版）。 */
private val SUPPORTED_PACKAGES = setOf(COEXISTENCE_PKG)

/**
 * 该包名是否为目标游戏包（仅共存版）。
 *
 * ⚠️ 用于 `AlaMobileModule.onPackageReady` 的**包名白名单**：该回调对**游戏进程内
 * 加载的每一个包**都会触发——实测 `com.google.android.gms`（×2）与
 * `com.google.android.webview`（×1）在游戏进程内被加载时也各触发一次
 * （`isFirstPackage=false`）。若放行，deferred block 会为它们各注册一个
 * `ConfigReceiver`、各跑一遍 `ShadowHook.init` + `forceLoad` + `initUnlock`。
 * 模块的一切（overlay / native hook / config）都是游戏专属，非游戏包必须跳过。
 * 见 [[config-save-5x-rebuild]]（5 份 receiver ⇒ 一次配置广播重建 overlay 5 次）。
 */
fun isSupportedPackage(packageName: String?): Boolean =
    packageName != null && packageName in SUPPORTED_PACKAGES

const val SUPPORTED_VERSION_NAME = "8.0.6"
const val SUPPORTED_VERSION_CODE = 200150

/**
 * 适配的**打包修订号**（2026-10-18 从「最低值」改为「精确相等」）。
 *
 * ## 为什么是精确相等
 * 旧实现是 `fix >= MIN_COEX_FIX`（最低门槛），只保证"不太旧"，**不保证不太新**：
 * Fix 3/4/5 只要引擎版本号没动就一律放行。但 Fix 后缀同时是"安装包行为"的
 * 标记——补权限、改 NPatch 打包参数、调 manifest 都可能落在 Fix 里。放行未知
 * 的更高 Fix 等于对未验证过的包形态开绿灯。
 *
 * 现语义：**只认这一个 Fix 号**。发 Fix 3 时必须把常量改成 3 并随模块发版——
 * 这也正好让"游戏包与模块版本必须配套"变得显式。
 *
 * ## 两个维度仍然正交
 * - **引擎维度** = [SUPPORTED_VERSION_NAME] + [SUPPORTED_VERSION_CODE]：管"偏移是否
 *   安全"。升到 8.0.7 时改这里，fix 重新从 0 起算。
 * - **打包维度** = [SUPPORTED_COEX_FIX]：管"是不是**这一个**打包"。
 *
 * ## 修订历史
 * - **最低值 = 1**（2026-10-06）：引入打包修订号维度，拦截无后缀旧共存包（Fix 0）。
 * - **最低值 = 2**（2026-10-16）：强制存量用户升到 Fix 2。动因 = 旧共存包配 NPatch
 *   1.0.8 及更早存在"模块经常不生效"（缓存 APK 未设只读 → Android 14+ 拒绝加载
 *   可写 dex，见 `docs/NPATCH_CACHE_CRASH_NOTES.md` §11）。
 * - **精确相等 = 2**（2026-10-18）：用户定案"版本门控要严格等于，而不是大于等于
 *   放行"。当前适配包 = `Ala Mobile Pro 8.0.6 Fix 2`（NPatch 845）。
 */
const val SUPPORTED_COEX_FIX = 2

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
 * 版本判据（2026-10-18 起仅共存版，且**逐项精确相等**）：
 * `packageName == 共存版包名` 且 `versionCode == 200150` 且
 * `base == "8.0.6"` 且 `fix == [SUPPORTED_COEX_FIX]`。
 *
 * ⚠️ 三个维度全部**严格相等**，不是"至少"：
 * - 官版已停止维护 ⇒ 包名不匹配直接 false（官版 APK 无 VIBRATE，抓地力反馈
 *   只能走 Fallback 叩击，不是我们能承诺的体验）。
 * - `fix > SUPPORTED_COEX_FIX` 同样拒绝：见 [SUPPORTED_COEX_FIX] 的说明。
 * - `base` 用解析出的版本，不用 `versionName` 整体比较——`"8.0.6 Fix 2"` 与
 *   `"8.0.6"` 的 base 都是 `"8.0.6"`，但 fix 维度会把后者挡掉。
 */
fun isSupportedVersion(packageName: String, versionName: String?, versionCode: Long): Boolean {
    if (packageName != COEXISTENCE_PKG) return false
    if (versionCode != SUPPORTED_VERSION_CODE.toLong()) return false
    val gv = parseGameVersionName(versionName) ?: return false
    return gv.base == SUPPORTED_VERSION_NAME && gv.fix == SUPPORTED_COEX_FIX
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
 * 排查"旧共存版被拦截"时能直接看出用户装的是 `"8.0.6 Fix 1"`（旧）还是 `"8.0.6 Fix 2"`（当前）。
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
