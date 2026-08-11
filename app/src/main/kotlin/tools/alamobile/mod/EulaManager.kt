package tools.alamobile.mod

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 用户条款（EULA）接受状态管理。
 *
 * 每次修改条款内容（[EULA_SECTIONS]）时，必须递增 [EULA_VERSION] 常量。
 * 已接受的版本号低于当前版本 → 视为未同意，重新弹窗。
 *
 * 存储策略：**只存模块进程 filesDir**（`eula_accepted_version.flag` 文件存版本号）。
 *
 * 为什么不用 Remote Preferences：EULA 同意是"每个安装会话"的本地状态，只在
 * ConfigActivity（模块设置 UI 进程）里判断，不需要跨进程同步（remote prefs 是给
 * 游戏进程读配置用的）。若存 remote，`pm clear`/卸载重装**清不掉** LSPosed daemon
 * SQLite 里的残留标记——导致"清除数据后仍显示已接受、不弹协议"（用户实测 bug）。
 * 只存 filesDir，`pm clear`/卸载重装自然清掉，语义正确。
 */
object EulaManager {

    private const val TAG = "AlaMobileTool"

    /**
     * 当前用户条款版本号。
     *
     * ⚠️ 每次修改 [EULA_SECTIONS] 时必须递增此值，否则旧版用户升级后不会重新弹窗。
     * 版本号设计为线性递增正整数，不依赖语义版本。
     */
    const val EULA_VERSION = 2

    private const val EULA_FLAG_FILE = "eula_accepted_version.flag"

    data class EulaSection(
        val title: String,
        val body: String
    )

    /**
     * 用户条款各章节。
     *
     * 修改此处内容时，必须同步递增 [EULA_VERSION]。
     * 每个章节渲染为：粗体标题 + 正文。
     */
    val EULA_SECTIONS: List<EulaSection>
        get() = listOf(
            EulaSection(
                title = "1. 仅供学习研究",
                body = "本软件（Ala Mobile Tool）仅用于个人学习、技术研究和逆向工程教学，无任何商业目的。"
            ),
            EulaSection(
                title = "2. 免责声明",
                body = "本软件按「原样」提供，不提供任何明示或暗示的担保，包括但不限于适销性、特定用途适用性和非侵权性。"
            ),
            EulaSection(
                title = "3. 责任限制",
                body = "在任何情况下，开发者和贡献者均不对任何直接、间接、附带、特殊或后果性损害承担责任，包括但不限于设备损坏、数据丢失、游戏账号封禁等。"
            ),
            EulaSection(
                title = "4. 封号风险",
                body = "本软件会修改目标游戏（Ala Mobile）的运行时行为，可能违反游戏服务条款。使用本软件可能导致账号被暂时或永久封禁。在线/多人模式下风险更高，请自行评估并承担后果。"
            ),
            EulaSection(
                title = "5. 24 小时删除",
                body = "使用者应在下载后 24 小时内仅用于学习研究，之后请删除本软件。如需畅玩游戏，请通过官方渠道获取正版体验。"
            ),
            EulaSection(
                title = "6. 禁止行为",
                body = "严禁将本软件用于任何商业盈利活动，包括但不限于代练、刷排名、账号交易、非法牟利等。严禁违反《中华人民共和国网络安全法》及相关法律法规。"
            ),
            EulaSection(
                title = "7. 隐私声明",
                body = "本软件不收集、不存储、不上传任何个人身份信息、设备信息或游戏数据。所有配置数据仅存储在本地，不发送至任何远程服务器。"
            ),
            EulaSection(
                title = "8. 知识产权",
                body = "本软件源代码以 Apache-2.0 协议开源。游戏名称、素材、商标等版权归其各自所有者。本软件与游戏开发商、发行商无任何关联，未获其认可或授权。"
            )
        )

    /**
     * 条款末尾声明。
     */
    const val EULA_FOOTER = "使用即代表同意以上条款"

    /**
     * 检查用户是否已接受当前版本条款。
     *
     * 读取优先级：Remote Preferences → filesDir 文件兜底。
     */
    fun isAccepted(context: Context): Boolean {
        val version = readAcceptedVersion(context)
        Log.i(TAG, "EulaManager.isAccepted: stored=$version current=$EULA_VERSION accepted=${version >= EULA_VERSION}")
        return version >= EULA_VERSION
    }

    /**
     * 用户接受当前版本条款。
     *
     * 写模块进程 filesDir（`eula_accepted_version.flag`）。只存本地——
     * `pm clear`/卸载重装会一并清掉，符合"清除数据后重新弹协议"的语义。
     */
    fun accept(context: Context) {
        try {
            File(context.filesDir, EULA_FLAG_FILE).writeText(EULA_VERSION.toString())
            Log.i(TAG, "EulaManager: accepted v$EULA_VERSION via local file")
        } catch (e: Throwable) {
            Log.w(TAG, "EulaManager: accept write failed", e)
        }
    }

    /**
     * 清除 EULA 接受标记（删除 filesDir flag 文件）。
     *
     * 由 [LsposedStatus.clearAll]（设置页「清除激活标记」）调用：用户手动清除后，
     * 下次启动会重新弹出用户协议。
     */
    fun clear(context: Context) {
        try {
            File(context.filesDir, EULA_FLAG_FILE).delete()
            Log.i(TAG, "EulaManager: cleared local file")
        } catch (e: Throwable) {
            Log.w(TAG, "EulaManager: clear failed", e)
        }
    }

    /**
     * 读取已接受的版本号。
     *
     * 只读模块进程 filesDir 的 flag 文件。返回 -1 表示从未接受过（首次安装或标记被清除）。
     */
    private fun readAcceptedVersion(context: Context): Int {
        return try {
            val file = File(context.filesDir, EULA_FLAG_FILE)
            if (file.exists()) {
                file.readText().trim().toIntOrNull() ?: -1
            } else -1
        } catch (e: Throwable) {
            Log.w(TAG, "EulaManager: read failed", e)
            -1
        }
    }
}