package tools.alamobile.mod.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import org.json.JSONObject
import tools.alamobile.mod.overlay.OverlayManager

/**
 * 接收 ConfigActivity 发来的配置更新广播，写入游戏进程自己的
 * getExternalFilesDir（游戏进程天然可写，绕开包可见性和 scoped storage）。
 *
 * 广播方案：ConfigActivity（模块进程）改配置 → 写模块 filesDir 备份 +
 * 发定向广播 Intent(ACTION).setPackage(gamePkg) 带 JSON → 系统直接派发给
 * 游戏包，不查 PackageManager 可见性 → 游戏进程 ConfigReceiver 收到 →
 * 合并写自己 externalFilesDir（保留游戏进程已有的 position 字段）→
 * OverlayManager 读同一路径生效 + notifyConfigChanged 触发重建。
 *
 * 合并写：广播 JSON 不含 position 三字段（ConfigActivity 不管 position，
 * position 由游戏进程拖拽时 saveOverlayPosition 写）。ConfigReceiver 收到
 * 后读已有 JSON，用 incoming 覆盖非 position 字段，position 字段保留
 * 游戏进程已有——否则直接 writeText 覆盖会丢失拖拽保存的 position。
 *
 * 首次安装/首次启动时 receiver 还没注册（要等 onPackageReady），读到默认值；
 * 用户改一次配置后重启游戏，receiver 接收并写入，之后即时生效。
 */
class ConfigReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIG_UPDATE = "tools.alamobile.mod.CONFIG_UPDATE"
        const val EXTRA_JSON = "json"
        private const val FILE_NAME = "ala_tool_config.json"
        private const val TAG = "AlaMobileTool"

        // position 字段由游戏进程持有（拖拽时 saveOverlayPosition 写），
        // ConfigActivity 广播的 JSON 不含这三字段。合并时跳过，保留游戏进程已有值。
        // 定义在 ModConfig.POSITION_KEYS，这里复用避免重复维护。
        private val POSITION_KEYS = ModConfig.POSITION_KEYS
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIG_UPDATE) return
        val json = intent.getStringExtra(EXTRA_JSON)
        if (json.isNullOrEmpty()) {
            Log.w(TAG, "ConfigReceiver: empty json")
            return
        }
        try {
            // 用游戏进程自己 context 的 externalFilesDir——游戏进程天然可写，
            // 无需权限，无 scoped storage 限制。OverlayManager 后续读同一路径。
            val dir = context.getExternalFilesDir(null)
            if (dir == null) {
                Log.w(TAG, "ConfigReceiver: externalFilesDir null")
                return
            }
            val file = File(dir, FILE_NAME)

            // 合并写：读已有 JSON（拖拽保存的 position 在里面），用 incoming
            // 覆盖非 position 字段，position 字段保留游戏进程已有。
            val existing = if (file.exists()) {
                try {
                    JSONObject(file.readText())
                } catch (_: Throwable) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }
            val incoming = JSONObject(json)
            for (key in incoming.keys()) {
                if (key !in POSITION_KEYS) {
                    existing.put(key, incoming[key])
                }
            }
            file.writeText(existing.toString(2))
            Log.i(
                TAG,
                "ConfigReceiver: merged ${json.length} bytes to ${file.absolutePath} " +
                    "pedalMode_in=${incoming.optString("pedal_mode", "?")} " +
                    "pedalMode_written=${existing.optString("pedal_mode", "?")} " +
                    "ts=${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}"
            )

            // 通知 OverlayManager 重建——PedalOverlayView 构造拷 settings 快照，
            // 光写文件不够，必须重建 view 才能让新配置流进去。post 到主线程
            // 保证 UI 操作（removeView/addView）在主线程执行。
            OverlayManager.notifyConfigChanged()
        } catch (e: Throwable) {
            Log.e(TAG, "ConfigReceiver: write failed", e)
        }
    }
}
