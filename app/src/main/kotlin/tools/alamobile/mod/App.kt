package tools.alamobile.mod

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块进程的 Application，负责连接 LSPosed daemon 的 XposedService。
 *
 * Remote Preferences 路线（libxposed API 102）的关键桥梁：
 * - 写入端（ConfigActivity，模块进程）必须经 [XposedService.getRemotePreferences] 写
 *   LSPosed daemon 的 SQLite 数据库，而非写自己 filesDir（daemon 读不到模块沙箱）。
 * - [XposedServiceHelper.registerListener] 异步绑定 service，[onServiceBind] 回调里
 *   拿到 [XposedService] 实例存静态变量，供 [tools.alamobile.mod.config.ModConfig.write] 用。
 * - XposedProvider（service AAR 自带，manifest 由 AGP 自动合并）在模块进程启动时
 *   被 LSPosed 框架触发 call("SendBinder")，把 daemon 的 IBinder 传给模块进程。
 *
 * 异步陷阱：[onCreate] 调 registerListener 后，[onServiceBind] 在未来某个时刻才回调。
 * ConfigActivity.onCreate 时 [.xposedService] 可能仍为 null——[ModConfig.write] 必须
 * fallback：service 为 null 时仍写 filesDir + 发广播（旧方案兜底，零回归）。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val TAG = "AlaMobileTool"
        const val PREF_GROUP = "ala_mobile_tool"
        const val KEY_CONFIG_JSON = "config_json"

        // 激活状态相关 key（存同一份 Remote Preferences）。
        // module_loaded：模块 onModuleLoaded 执行时写 "1"，作为"曾被框架加载"的持久信号。
        // nonroot_confirmed：用户在弹窗里确认用了 LSPatch/NPatch/FPA 等 Non-root 框架后写 "1"。
        const val KEY_MODULE_LOADED = "module_loaded_v1"
        const val KEY_NONROOT_CONFIRMED = "nonroot_confirmed_v1"

        @Volatile
        var xposedService: XposedService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        try {
            XposedServiceHelper.registerListener(this)
            Log.i(TAG, "App: XposedServiceHelper listener registered")
        } catch (e: Throwable) {
            Log.w(TAG, "App: failed to register XposedServiceHelper listener", e)
        }
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        Log.i(TAG, "App: XposedService bound")
    }

    override fun onServiceDied(service: XposedService) {
        xposedService = null
        Log.w(TAG, "App: XposedService died")
    }
}
