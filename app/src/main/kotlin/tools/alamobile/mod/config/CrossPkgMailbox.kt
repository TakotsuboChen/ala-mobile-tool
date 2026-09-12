package tools.alamobile.mod.config

import android.os.Environment
import tools.alamobile.mod.util.Logger
import java.io.File

/**
 * 跨包"信箱"通道（2026-09-12 定案）：`/sdcard/Android/media/<游戏包>/`。
 *
 * **为什么是这个目录**（实机验证 + 官方文档）：
 * - `Android/data/<他包>/`：官方明文，AFA（MANAGE_EXTERNAL_STORAGE）也**排除**他包 ASD
 *   ——实机 `ENOENT`（内核直接隐藏路径）。
 * - `Android/media/<他包>/`：**不在受限区**。AFA 前 `EPERM`（路径真实存在、仅权限不足），
 *   AFA 后**可读可写**（实机验证：模块 App 写 36 字节 → 游戏进程读到同内容）。
 * - 游戏进程读**自己包**的 media 目录 = 同 uid 直读，**无需任何权限**。
 *
 * **为什么能绕开所有已知障碍**：
 * - 不经 AMS → ColorOS 启动管控（OplusAppStartupManager）用不上
 * - 不经组件拉起 → 游戏 stopped state（force-stop/划掉）也不影响
 * - 写侧是纯内核文件写 → 模块 App 之后死不死无所谓
 * - 读侧游戏直读自己目录 → 模块进程死不死无所谓
 *
 * **代价**：模块 App 需开一次「所有文件访问」（AFA）。这是唯一前置，且是系统标准
 * 入口（可被系统重置的概率远低于 ColorOS 的"关联启动"开关）。
 *
 * ⚠️ 写侧只在模块进程有效（ConfigActivity）；游戏进程写自己的目录也走这里。
 * 未授 AFA 时 write 返回 false，调用方静默降级到原有通道。
 */
object CrossPkgMailbox {

    private const val TAG = "AlaMobileTool"
    private const val DIR = "Android/media"

    /** 目标游戏包（原版 + 共存版）。写入侧对两个包各写一份。 */
    val GAME_PACKAGES = listOf(
        "com.Takotsubo.AlamobileFormula",
        "com.Vince.AlamobileFormula",
    )

    /** 配置信箱文件名（每个游戏包各自一份）。 */
    const val CONFIG_FILE = "ala_tool_config.json"

    /** 登录态信箱文件名。 */
    const val AUTH_FILE = "paddock_auth.json"

    /** 信箱文件路径：`/sdcard/Android/media/<pkg>/<name>`。 */
    fun file(pkg: String, name: String): File =
        File(Environment.getExternalStorageDirectory(), "$DIR/$pkg/$name")

    /**
     * 写信箱（模块进程写游戏包目录需 AFA；游戏进程写自己目录无需权限）。
     * @return true=写入成功
     */
    fun write(pkg: String, name: String, content: String): Boolean = try {
        val f = file(pkg, name)
        f.parentFile?.mkdirs()
        f.writeText(content)
        Logger.i(TAG, "Mailbox write OK $pkg/$name (${content.length}B)")
        true
    } catch (e: Throwable) {
        Logger.w(TAG, "Mailbox write failed $pkg/$name: ${e::class.java.simpleName}: ${e.message}")
        false
    }

    /** 读信箱。文件不存在/无权限 → null。 */
    fun read(pkg: String, name: String): String? = try {
        val f = file(pkg, name)
        if (f.exists()) {
            val t = f.readText()
            Logger.i(TAG, "Mailbox read OK $pkg/$name (${t.length}B)")
            t
        } else {
            null
        }
    } catch (e: Throwable) {
        Logger.w(TAG, "Mailbox read failed $pkg/$name: ${e::class.java.simpleName}: ${e.message}")
        null
    }

    /** 删信箱文件（登出/清态用）。 */
    fun clear(pkg: String, name: String): Boolean = try {
        val f = file(pkg, name)
        val ok = !f.exists() || f.delete()
        Logger.i(TAG, "Mailbox clear $pkg/$name → $ok")
        ok
    } catch (e: Throwable) {
        Logger.w(TAG, "Mailbox clear failed $pkg/$name: ${e.message}")
        false
    }
}
