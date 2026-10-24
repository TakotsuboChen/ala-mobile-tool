package tools.alamobile.mod.util

import android.content.Context
import android.os.Build
import tools.alamobile.mod.BuildConfig
import tools.alamobile.mod.util.COEXISTENCE_PKG
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动环境信息块——一次采集、一行一字段写入日志。
 *
 * ## 为什么要有它（2026-10-18 实证教训）
 *
 * 排查"卡加载"时，用户报的版本（"9/18 的 Alpha 1"）与实际构建**差了四天**，
 * 而 `versionName` / `versionCode` 完全锁不住：`1.0.4 Alpha 1` / `104100`
 * 从 9/5 沿用到 10/6，横跨几十个提交。当时只能靠"日志里有没有某个已删除的
 * 字符串"倒推构建区间，非常被动。
 *
 * 同理，跨品牌振感问题查了十几轮，每次都要先问"你什么机型什么系统"——
 * 这些信息本就该在日志里。
 *
 * ⇒ 每次进程启动打一次完整环境块，让任何一份导出日志**自带全部上下文**。
 *
 * ## 为什么分成两个进程打
 *
 * 模块进程（ConfigActivity）与游戏进程（AlaMobileModule）各写各的日志文件，
 * 两边的环境信息**几乎相同但游戏版本只有游戏进程读得到**（模块进程读游戏
 * 包信息需要 queries 声明，已有；但游戏进程里 `context.packageName` 就是
 * 游戏包，更直接）。两个进程都打一份，导出时两段日志各自完整。
 *
 * ## 采集失败一律不抛
 *
 * 环境信息是诊断辅助，任何一项读不到就写 `?`，绝不能让日志初始化失败——
 * 那会让整个模块失去诊断能力，比缺一行信息严重得多。
 */
object EnvInfo {

    private const val TAG = "EnvInfo"

    /** 环境块分隔标记——导出日志时可直接 grep `=== 环境信息` 定位。 */
    private const val BEGIN_MARK = "=== 环境信息 ==="
    private const val END_MARK = "=== 环境信息结束 ==="

    /**
     * 打印环境块。**每个进程只应调用一次**（在 `Logger.init` 之后）。
     *
     * @param context 当前进程的 Context；为 null 时仍打印能拿到的部分
     *   （模块版本、构建指纹、Build.* 都不依赖 Context）
     * @param isModuleProcess true = 模块进程，日志里标注来源
     */
    fun log(context: Context?, isModuleProcess: Boolean) {
        try {
            val sb = StringBuilder()
            sb.append(BEGIN_MARK).append('\n')

            // ── 进程身份：一眼看出这段日志来自哪个进程 ──
            sb.append("进程: ").append(if (isModuleProcess) "模块进程" else "游戏进程").append('\n')

            // ── 模块自身：版本 + 构建指纹 ──
            sb.append("模块: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            sb.append("构建时间: ").append(formatBuildTime()).append('\n')
            sb.append("构建提交: ").append(BuildConfig.GIT_SHA).append('\n')

            // ── 游戏包：版本 + Fix 号（Fix 号决定门控是否放行）──
            sb.append("游戏: ").append(gameVersion(context)).append('\n')

            // ── 框架（2026-10-24 新增）──
            // 这是 NPatch 门控的判据来源，也是排查"功能不生效"的第一现场：
            // frameworkName 区分 LSPosed/NPatch，versionCode 决定放不放行。
            // ⚠️ 不用 frameworkVersion（versionName）——NPatch 846 上它硬编码
            // 返回 "v1.0.7"，与包内 config.json 的 1.0.9 都对不上。
            sb.append("框架: ").append(npatchSummary(context)).append('\n')

            // ── 设备 ──
            sb.append("厂商: ").append(Build.MANUFACTURER).append('\n')
            sb.append("品牌: ").append(Build.BRAND).append('\n')
            sb.append("型号: ").append(Build.MODEL).append('\n')
            sb.append("设备代号: ").append(Build.DEVICE).append('\n')

            // ── 系统：Android 版本 + ROM 名称/版本 ──
            sb.append("安卓版本: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            sb.append("系统: ").append(romName()).append('\n')
            sb.append("系统指纹: ").append(Build.FINGERPRINT).append('\n')

            // ── 运行时 ──
            sb.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
            sb.append("构建类型: ").append(if (BuildConfig.DEBUG) "debug" else "release").append('\n')

            sb.append(END_MARK)
            Logger.i(TAG, sb.toString())
        } catch (e: Throwable) {
            // 采集失败不能影响启动——但也要留痕，否则"日志里没有环境块"无从解释
            Logger.w(TAG, "环境信息采集失败: ${e.message}")
        }
    }

    /**
     * 构建时间：`BUILD_TIME_MILLIS` → 可读本地时间。
     *
     * 用 `yyyy-MM-dd HH:mm:ss`（精确到秒）而非日期——同一天可能有多次构建，
     * 排查时需要区分"上午那个包"和"下午那个包"。
     */
    private fun formatBuildTime(): String {
        val millis = BuildConfig.BUILD_TIME_MILLIS
        if (millis <= 0L) return "?"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
        } catch (_: Throwable) {
            millis.toString()
        }
    }

    /**
     * 游戏包版本，形如 `8.0.6 Fix 2 (200150)`。
     *
     * 读不到时返回 `?`——模块进程在某些 ROM 上可能因包可见性读不到游戏包，
     * 这不是错误（游戏进程自己一定能读到）。
     *
     * ⚠️ 游戏包名固定用 [COEXISTENCE_PKG]，**不能**用 `context.packageName`：
     * 模块进程里 `context.packageName` 是模块自己（`tools.alamobile.mod`），
     * 游戏进程里才是游戏包——两处语义不同，用错会打印出模块自己的版本。
     */
    private fun gameVersion(context: Context?): String {
        if (context == null) return "?"
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    COEXISTENCE_PKG,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(COEXISTENCE_PKG, 0)
            }
            val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
            "${info.versionName} ($code)"
        } catch (_: Throwable) {
            "?"
        }
    }

    /**
     * ROM 名称与版本，形如 `HyperOS / Android 15`、`Flyme 12`、`OriginOS 6`。
     *
     * ## 为什么不用 `Build.DISPLAY`
     *
     * `DISPLAY` 是构建号（如 `AP1A.240405.002`），对判断"哪家 ROM"毫无帮助。
     * 各家 ROM 把自家标识放在不同的系统属性里，且**属性名互不相同**：
     *
     * | ROM | 属性 | 示例值 |
     * |---|---|---|
     * | 小米 HyperOS/MIUI | `ro.mi.os.version.name` / `ro.miui.ui.version.name` | `OS2.0` / `V14` |
     * | OPPO/一加 ColorOS | `ro.build.version.oplusrom` | `V15.0.1` |
     * | vivo OriginOS | `ro.vivo.os.version` | `15.0` |
     * | 魅族 Flyme | `ro.build.display.id` | `Flyme 12.0.1` |
     * | 华为 HarmonyOS | `ro.build.version.harmony` | `4.2` |
     *
     * ⇒ 逐个探测，命中即返回。全部未命中时回落 `Build.DISPLAY`（至少不是空）。
     *
     * ## 为什么用反射读系统属性
     *
     * `android.os.SystemProperties` 是 **hidden API**，SDK 里没有。反射调用
     * `get(String)` 在 Android 9+ 受 non-SDK 限制，但 `SystemProperties` 属于
     * **greylist**（`get` 被大量应用使用，AOSP 未封锁）——实测各家 ROM 均可读。
     * 万一被拦截（`NoSuchMethodException` / `InvocationTargetException`），
     * 静默回落到 `Build.DISPLAY`，不影响其余字段。
     */
    private fun romName(): String {
        // 属性名 → 该属性值**本身是否已含 ROM 名**。
        //
        // 为什么需要映射而不是直接输出值：各家属性的值形态差异极大——
        // `ro.mi.os.version.name` 的值是 `OS2.0`（看不出是小米）、
        // `ro.vivo.os.version` 是 `15.0`（看不出是 vivo），直接输出等于
        // 丢掉了 ROM 身份。只有 Flyme 的 `ro.build.display.id` 值自带
        // 名字（`Flyme 12.6.0.0A`），故它的 label 留空。
        val props = listOf(
            "ro.mi.os.version.name" to "HyperOS",
            "ro.miui.ui.version.name" to "MIUI",
            "ro.build.version.oplusrom" to "ColorOS",
            "ro.vivo.os.version" to "OriginOS",
            "ro.build.version.harmony" to "HarmonyOS",
            "ro.build.display.id" to "",      // Flyme 等：值本身含 ROM 名
        )
        for ((key, label) in props) {
            val v = systemProperty(key)
            if (!v.isNullOrBlank()) {
                return if (label.isEmpty()) v else "$label $v"
            }
        }
        return Build.DISPLAY
    }

    /**
     * 反射读 `android.os.SystemProperties.get(String)`。失败返回 null。
     *
     * 每次调用都重新反射（不缓存 Method）——环境块每进程只打一次，
     * 缓存的复杂度不值得。
     */
    private fun systemProperty(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}
