package tools.alamobile.mod

import android.content.Context
import android.util.Log

/**
 * 模块激活状态判定。
 *
 * 用户规则：
 * - 真被 LSPosed（或 Non-root 框架）激活 → 显示"已激活" + "模块已通过 LSPosed 加载"。
 * - 未检测到激活 → 显示"未激活" + "点击确认是否使用了免 Root 框架"。
 * - 点击卡片弹窗问"是否安装了 LSPatch/NPatch/FPA 等免 Root 框架"。
 *   - 选"是" → 写 nonroot_confirmed 标记 → 显示"已激活" + "模块已通过 Non-root LSPosed 加载"。
 *   - 选"否" → 保持未激活。
 * - 迁移规则：LSPosed 真激活会覆盖并忘掉 Non-root 标记（清 nonroot_confirmed）；
 *   之后 LSPosed 没了 → 不保留 Non-root 已激活状态，必须重新点选。
 *
 * 真激活信号源：
 * 1. 进程级 [AlaMobileModule.MODULE_LOADED_FLAG]（System.setProperty）—— 严格反映
 *    "当前 ConfigActivity 进程本次启动是否被框架调用了 onModuleLoaded"。
 *    LSPosed 关掉模块后重启进程，property 自然不存在，立即变未激活。
 * 2. Remote Preferences 的 module_loaded 标记 —— onModuleLoaded 同时写 daemon
 *    持久化，作为"曾被加载过"的补充信号（用户关了 LSPosed Manager 但进程未重启时
 *    property 仍在，daemon 标记也仍在——两者都不会自动反映"Manager 关了"。
 *    真正区分"当前是否还在 LSPosed 下运行"靠 property，因为它随进程生命周期）。
 *
 * 时序：onModuleLoaded 不保证在 Application.onCreate 之前调用。ConfigActivity
 * 首次读检测时 property 可能还没设上。[evaluate] 提供带轮询的读取兜住这个窗口。
 */
object LsposedStatus {

    private const val TAG = "AlaMobileTool"

    /** 激活状态。UI 据此渲染卡片标题、描述、颜色与点击行为。 */
    enum class Status {
        /** 真被 LSPosed 加载（onModuleLoaded 在当前进程执行过）。 */
        LSPOSED,
        /** 用户确认用 Non-root 框架（LSPatch/NPatch/FPA）加载。 */
        NONROOT,
        /** 未激活。 */
        INACTIVE
    }

    /**
     * 判定当前激活状态。
     *
     * @param context ConfigActivity 的 context（模块进程）
     * @param awaitModuleLoad 是否轮询等待进程级 module_loaded 标记（处理框架注入
     *   时序晚于读检测的情况）。首次进入页面时传 true，后续手动刷新传 false。
     */
    fun evaluate(context: Context, awaitModuleLoad: Boolean = false): Status {
        // 1) 进程级标记 —— 当前进程本次启动真的被框架加载过。
        if (System.getProperty(AlaMobileModule.MODULE_LOADED_FLAG) == "true") {
            // LSPosed 真激活 → 覆盖并忘掉 Non-root 标记（满足迁移规则）。
            clearNonRootConfirmed(context)
            return Status.LSPOSED
        }

        // 时序兜底：onModuleLoaded 可能晚于读检测。轮询等待最多 ~3s。
        if (awaitModuleLoad) {
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                if (System.getProperty(AlaMobileModule.MODULE_LOADED_FLAG) == "true") {
                    clearNonRootConfirmed(context)
                    return Status.LSPOSED
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }

        // 2) Non-root 用户确认标记 —— 用户在弹窗里选了"是"。
        if (readNonRootConfirmed(context)) {
            return Status.NONROOT
        }

        return Status.INACTIVE
    }

    /** 用户在弹窗里选"是"（用了 Non-root 框架）→ 写持久标记。 */
    fun confirmNonRoot(context: Context) {
        val service = App.xposedService
        if (service != null) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .putString(App.KEY_NONROOT_CONFIRMED, "1")
                    .apply()
                Log.i(TAG, "confirmNonRoot: remote prefs updated")
                return
            } catch (e: Throwable) {
                Log.w(TAG, "confirmNonRoot: remote prefs failed, trying local", e)
            }
        }
        // 兜底：service 没绑上时写模块 filesDir（ConfigActivity 进程可写）。
        try {
            val file = java.io.File(context.filesDir, "nonroot_confirmed.flag")
            file.writeText("1")
        } catch (e: Throwable) {
            Log.w(TAG, "confirmNonRoot: local fallback failed", e)
        }
    }

    /** 用户在弹窗里选"否" → 清掉 Non-root 标记，保持未激活。 */
    fun clearNonRootConfirmed(context: Context) {
        val service = App.xposedService
        if (service != null) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .remove(App.KEY_NONROOT_CONFIRMED)
                    .apply()
            } catch (_: Throwable) {}
        }
        try {
            java.io.File(context.filesDir, "nonroot_confirmed.flag").delete()
        } catch (_: Throwable) {}
    }

    /** "设置 → 清除激活标记"用：彻底清掉所有激活痕迹。 */
    fun clearAll(context: Context) {
        clearNonRootConfirmed(context)
        val service = App.xposedService
        if (service != null) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .remove(App.KEY_MODULE_LOADED)
                    .apply()
            } catch (_: Throwable) {}
        }
        try {
            System.clearProperty(AlaMobileModule.MODULE_LOADED_FLAG)
        } catch (_: Throwable) {}
    }

    private fun readNonRootConfirmed(context: Context): Boolean {
        val service = App.xposedService
        if (service != null) {
            try {
                val v = service.getRemotePreferences(App.PREF_GROUP)
                    .getString(App.KEY_NONROOT_CONFIRMED, null)
                if (v == "1") return true
            } catch (e: Throwable) {
                Log.w(TAG, "readNonRootConfirmed: remote prefs failed", e)
            }
        }
        // 兜底读本地。
        return try {
            java.io.File(context.filesDir, "nonroot_confirmed.flag").exists() &&
                java.io.File(context.filesDir, "nonroot_confirmed.flag").readText() == "1"
        } catch (_: Throwable) { false }
    }

    /**
     * 旧 API 兼容：[OverviewPage] 原先直接拿 Boolean。
     * 保留以减少调用方改动，但语义已改为"非未激活"（LSPOSED 或 NONROOT）。
     */
    fun isActivated(context: Context): Boolean = evaluate(context) != Status.INACTIVE
}
