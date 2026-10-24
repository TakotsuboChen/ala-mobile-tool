package tools.alamobile.mod.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/**
 * 框架识别：区分 LSPosed（root）与 NPatch（非 root），并给出 NPatch 版本。
 *
 * ## 为什么需要它
 *
 * 模块同时支持两种加载方式，且两者对 NPatch 版本的要求完全不同：
 * - **LSPosed（root）**：不用 NPatch，没有版本可言 ⇒ 必须放行。
 * - **NPatch（非 root，绝大多数用户）**：必须用**与共存包配套的那个版本**
 *   （当前 850），否则模块行为未经验证。
 *
 * 判不出框架就无法决定拦不拦——这是 NPatch 门控的前置。
 *
 * ## 判据：`XposedModule` 自报，不是猜的
 *
 * libxposed API 102 的 `XposedModule` 基类自带 `frameworkName` /
 * `frameworkVersionCode`，由框架在 `attachFramework()` 时注入，
 * **在 `onModuleLoaded()` 之前就已完成**（NPatch 源码实证：
 * `LSPLoader.java` 的 attach 在 onModuleLoaded 前一行）。
 *
 * - NPatch：`VectorContext.getFrameworkName()` = `BuildConfig.FRAMEWORK_NAME` = `"NPatch"`；
 *   `getFrameworkVersionCode()` = `BuildConfig.VERSION_CODE` = **850**（= git commitCount）
 * - LSPosed：同一 API 返回 `"LSPosed"` 与它自己的版本号
 *
 * ⚠️ **不要用 `frameworkVersion`（versionName）判版本**：NPatch 846/850 上它返回
 * `"v1.0.7"`（上游 `core/build.gradle.kts` 硬编码未随发版更新），与包内
 * `config.json` 写的 `1.0.9` 都对不上。**只认 `frameworkVersionCode`。**
 *
 * ## 为什么不用其它信号（均已证伪）
 *
 * - `hasModuleLoadedFlag()`：**两种框架都会触发 `onModuleLoaded`**，区分不了。
 * - manifest `appComponentFactory`：NPatch 846 起改用 entry-hook shim，manifest
 *   保留宿主原类名（实测 Fix 2 包 = `androidx.core.app.CoreComponentFactory`，
 *   与未修补裸包完全一致）⇒ 静态指纹已消除。
 * - `applicationInfo.appComponentFactory`：`sigBypassLevel >= 2` 时 NPatch 会把
 *   它还原成宿主原值（共存包 `sigBypassLevel=5`）。
 * - `getApiVersion()` / `getFrameworkProperties()`：两框架同值。
 *
 * 详见 memory `npatch-version-gate-independent-dimension` 与
 * `docs/NPATCH_CACHE_CRASH_NOTES.md`。
 */

/**
 * 适配的 NPatch 框架版本（`frameworkVersionCode`）。用户定案：**精确等于**。
 *
 * 850 = NPatch 1.0.9（Fix 3 包配套版本，2026-10-25）。发新共存包时若换了 NPatch
 * 版本，必须同步改这里并随模块发版，否则用户装了新包会被拦。
 *
 * ## 版本沿革
 * - 846 = NPatch 1.0.9（Fix 2 包配套，2026-10-18 ~ 2026-10-25）
 * - 850 = NPatch 1.0.9（Fix 3 包配套，2026-10-25 起）——本版把签名绕过从 xHook
 *   （PLT/GOT 改写）迁移到 bhook（inline hook），并剥离框架自带的 ShadowHook 依赖。
 *   ⚠️ 模块自身的 inline hook **不受影响**：`libala-core.so` 的 `NEEDED` 指向
 *   `libshadowhook.so`，而该库是模块 APK 自己打包的，与框架无关。
 */
const val SUPPORTED_NPATCH_VERSION_CODE = 850L

/** NPatch 管理器包名。Android 11+ 包可见性已在 AndroidManifest `<queries>` 声明。 */
private const val NPATCH_MANAGER_PKG = "top.nkbe.npatch"

// ── 进程级 property 键（与 MODULE_LOADED_FLAG / NATIVE_INSTALLED_FLAG 同模式）──

/** 框架名，由 [recordFramework] 在 `onModuleLoaded` 写入。 */
const val FRAMEWORK_NAME_FLAG = "tools.alamobile.mod.framework_name"

/** 框架版本号（NPatch = commitCount），由 [recordFramework] 写入。 */
const val FRAMEWORK_VERSION_CODE_FLAG = "tools.alamobile.mod.framework_version_code"

/**
 * 框架种类。`Unknown` = 尚未 `onModuleLoaded` 或读 `frameworkName` 抛异常。
 */
enum class FrameworkKind {
    /** LSPosed（root）。不判 NPatch 版本。 */
    LSPOSED,

    /** NPatch（非 root）。判版本。 */
    NPATCH,

    /** 读不到 / 未知框架名。放行（fail-open，避免误伤未验证的第三方框架）。 */
    UNKNOWN;

    companion object {
        fun fromName(name: String?): FrameworkKind = when (name) {
            "LSPosed" -> LSPOSED
            "NPatch" -> NPATCH
            else -> UNKNOWN
        }
    }
}

/**
 * 由 `AlaMobileModule.onModuleLoaded()` 调用，把框架信息存进进程级 property。
 *
 * ⚠️ 必须在 `onModuleLoaded` 里调用（不是 `onPackageLoaded`）——前者更早，
 * 且此时 `attachFramework()` 一定已完成；`onPackageLoaded` 可能还没发生。
 *
 * @param name `XposedModule.frameworkName`，读取失败传 null
 * @param versionCode `XposedModule.frameworkVersionCode`，读取失败传 -1
 */
fun recordFramework(name: String?, versionCode: Long) {
    try {
        System.setProperty(FRAMEWORK_NAME_FLAG, name ?: "")
        System.setProperty(FRAMEWORK_VERSION_CODE_FLAG, versionCode.toString())
    } catch (_: Throwable) {
        // property 写失败不影响主流程（门控会退化为 UNKNOWN ⇒ 放行）
    }
}

/** 读本进程的框架种类（未 record 过 = [FrameworkKind.UNKNOWN]）。 */
fun currentFrameworkKind(): FrameworkKind = FrameworkKind.fromName(
    try { System.getProperty(FRAMEWORK_NAME_FLAG) } catch (_: Throwable) { null }
)

/** 读本进程的框架版本号；未 record 过返回 -1。 */
fun currentFrameworkVersionCode(): Long = try {
    System.getProperty(FRAMEWORK_VERSION_CODE_FLAG)?.toLongOrNull() ?: -1L
} catch (_: Throwable) {
    -1L
}

/**
 * NPatch 管理器 App 的 versionCode；未安装 / 查询失败返回 -1。
 *
 * 依赖 manifest `<queries>` 已声明 `top.nkbe.npatch`（API 30+ 包可见性）。
 */
fun npatchManagerVersionCode(context: Context?): Long {
    if (context == null) return -1L
    return try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                NPATCH_MANAGER_PKG,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(NPATCH_MANAGER_PKG, 0)
        }
        PackageInfoCompat.getLongVersionCode(info)
    } catch (_: PackageManager.NameNotFoundException) {
        -1L
    } catch (_: Throwable) {
        -1L
    }
}

/**
 * NPatch 门控判定结果。
 *
 * 每个分支对应一种明确的处置，避免调用方再拼条件（曾经把"未安装"和"版本不符"
 * 混成一句"去下载共存版"，用户指出这是"没水喝就去买辣条"）。
 */
sealed class NPatchVerdict {
    /** 放行：LSPosed 用户，或未知框架（fail-open）。 */
    data object Pass : NPatchVerdict()

    /** 拦：NPatch 环境下**没装管理器**。 */
    data object ManagerMissing : NPatchVerdict()

    /**
     * 拦：版本不符。`frameworkCode` = 注入时框架版本，`managerCode` = 管理器版本，
     * 任一不等于 [SUPPORTED_NPATCH_VERSION_CODE] 即命中（用户定案：两者都要 == 850）。
     */
    data class VersionMismatch(val frameworkCode: Long, val managerCode: Long) : NPatchVerdict()
}

/**
 * 门控判定。
 *
 * 判据（用户 2026-10-22/24 定案）：
 * 1. 非 NPatch 框架（LSPosed / 未知）→ [NPatchVerdict.Pass]
 * 2. NPatch 且**没装管理器** → [NPatchVerdict.ManagerMissing]（非 root 缺必要组件）
 * 3. NPatch 且 `frameworkVersionCode != 850` **或** 管理器 `versionCode != 850`
 *    → [NPatchVerdict.VersionMismatch]（两者都要精确等于 850）
 * 4. 否则 → [NPatchVerdict.Pass]
 */
fun evaluateNPatchGate(context: Context?): NPatchVerdict {
    if (currentFrameworkKind() != FrameworkKind.NPATCH) return NPatchVerdict.Pass

    val managerCode = npatchManagerVersionCode(context)
    if (managerCode < 0) return NPatchVerdict.ManagerMissing

    val frameworkCode = currentFrameworkVersionCode()
    return if (frameworkCode == SUPPORTED_NPATCH_VERSION_CODE &&
        managerCode == SUPPORTED_NPATCH_VERSION_CODE
    ) {
        NPatchVerdict.Pass
    } else {
        NPatchVerdict.VersionMismatch(frameworkCode, managerCode)
    }
}

/**
 * **模块进程（UI 侧）**的门控判定结果。
 *
 * 与 [NPatchVerdict] 的区别：这里**没有**框架维度——模块 App 是普通 App，
 * 永远不会被 Xposed 注入，`frameworkVersionCode` 在它进程里恒为 -1。
 * 详见 [evaluateManagerVersionGate]。
 */
sealed class ManagerVerdict {
    /** 放行：管理器版本与 [SUPPORTED_NPATCH_VERSION_CODE] 一致，或 context 不可用。 */
    data object Pass : ManagerVerdict()

    /** 拦：**没装 NPatch 管理器**。 */
    data object Missing : ManagerVerdict()

    /** 拦：管理器版本不符。[managerCode] 为实际值（供胶囊显示）。 */
    data class Mismatch(val managerCode: Long) : ManagerVerdict()
}

/**
 * 模块进程（概览页胶囊）用的判定：**只看 NPatch 管理器版本**（用户 2026-10-25 定案）。
 *
 * ## 为什么不能复用 [evaluateNPatchGate]
 *
 * [evaluateNPatchGate] 依赖 `frameworkName` / `frameworkVersionCode`，这两个值由
 * [recordFramework] 写进**进程级** `System.setProperty`，而 [recordFramework] 只在
 * 游戏进程的 `onModuleLoaded` 里被调用。模块进程是普通 App，从不被注入 ⇒
 * `currentFrameworkKind()` 恒为 `UNKNOWN` ⇒ 那个函数在模块进程里**恒返回 Pass**
 * （实机复现：门控已在游戏内生效，概览页胶囊却仍显示绿色「已适配」）。
 * 模块进程的胶囊走 [evaluateManagerVersionGate]。
 *
 * ## 判据的取舍（已知局限，用户已认可）
 *
 * 模块进程判不出"游戏到底被哪个框架加载"，只能查管理器的 `versionCode`。
 * 因此 **管理器版本 ≠ 游戏包内框架版本**时会误判为"已适配"——这种组合真实存在
 * （用户装了新管理器，但游戏包 `assets/npatch/config.json` 里的框架还是旧的，
 * 要重打包才能更新）。**游戏内的循环 Toast 仍是唯一的权威提示**。
 *
 * ⚠️ 反向也要注意：**不要把本函数用于游戏进程的门控**——那样会绕过框架判定，
 * 门控形同虚设。
 *
 * @return context 为 null 时 [ManagerVerdict.Pass]（fail-open，不误伤）
 */
fun evaluateManagerVersionGate(context: Context?): ManagerVerdict {
    if (context == null) return ManagerVerdict.Pass
    val code = npatchManagerVersionCode(context)
    return when {
        code < 0 -> ManagerVerdict.Missing
        code != SUPPORTED_NPATCH_VERSION_CODE -> ManagerVerdict.Mismatch(code)
        else -> ManagerVerdict.Pass
    }
}

/**
 * 供日志/UI：`"NPatch 850 / 管理器 850"` 这类一行摘要。
 *
 * 三个值都给出来——排查"为什么被拦"时，能立刻区分是框架版本旧、管理器版本旧、
 * 还是管理器没装。
 */
fun npatchSummary(context: Context?): String {
    val kind = currentFrameworkKind()
    if (kind != FrameworkKind.NPATCH) return kind.name
    val fw = currentFrameworkVersionCode()
    val mgr = npatchManagerVersionCode(context)
    return "NPatch 框架=$fw / 管理器=${if (mgr < 0) "未安装" else mgr.toString()}"
}
