package tools.alamobile.mod

import android.content.Context
import java.io.File
import tools.alamobile.mod.util.Logger

/**
 * 围场指南：内容 + 「已阅读」状态管理。
 *
 * 与 [EulaManager] 同模式：内容以 Markdown 字符串承载（由 ui/MarkdownText 渲染），
 * 已读状态只存**模块进程 filesDir** 的版本号 flag（`paddock_guide_seen.flag`）。
 *
 * 为什么不用 Remote Preferences：指南是否弹过是模块设置 UI 进程内部的本地状态，
 * 不需要跨进程同步；且存 local 后 `pm clear`/卸载重装自然清掉，语义正确（重装后
 * 应该重新引导）。这与 EULA 的存储决策完全一致。
 *
 * 触发时机（由 `PaddockPager` 判定）：
 * - 用户从围场卡片手动点开（随时）。
 * - 已登录且位于围场主页（root，非二级页）、且当前版本指南未读 → 自动弹出一次。
 *   注册后首次登录会先跳头像上传页（二级页），此时**不弹**；从头像页返回主页才弹。
 */
object PaddockGuide {

    private const val TAG = "AlaMobileTool"

    /**
     * 当前指南版本号。
     *
     * ⚠️ 修改 [GUIDE_MARKDOWN] 时递增此值，让已读旧版的用户升级后重新看到新指南。
     */
    const val GUIDE_VERSION = 1

    private const val GUIDE_FLAG_FILE = "paddock_guide_seen.flag"

    /**
     * 指南正文（Markdown）。问答形式，覆盖：围场是什么 / 怎么上榜 / 积分规则 /
     * 积分为何浮动 / 是否丢成绩。
     *
     * 语法受 [tools.alamobile.mod.ui.MarkdownText] 限制：支持 `##`/`###` 标题、
     * `**粗体**`、无序/有序列表、行内代码、分隔线；**不支持表格、引用块、
     * 三级以上标题、粗斜体（`***`）**，故积分举例用列表而非表格、公式用纯粗体。
     */
    const val GUIDE_MARKDOWN: String = """### 1. 围场是什么？

围场是 Ala Mobile 的第三方计时赛成绩平台。你在游戏里跑出的有效圈速会自动上传到这里，按赛道汇总成榜单；榜单上的名次决定你能拿多少积分。

围场由社区自建，**与游戏官方无关**。

### 2. 我要怎么才能上计时赛排行榜？

- 进入游戏的**计时赛**模式，跑出一个有效圈；
- 有效圈会实时自动上传，无需手动录入，上传成功后，你的个人最快圈速将立刻出现在对应赛道的榜单上；
- 只有计时赛模式的有效圈才会计入。上传失败时成绩会先存在本地，联网后自动补传，不需要重跑。

### 3. 计时赛积分规则是怎么样的？

积分按**每条赛道 × 每个游戏版本**分别计算，名次由该赛道的最快圈速决定。单条赛道积分公式为：

**积分 = (N + 1 - 名次) × 100 ÷ N**

其中 N 是该赛道有成绩的人数。也就是说：第 1 名永远 100 分，最后一名为 100 ÷ N 分，不能整除的四舍五入。

举个例子，某条赛道有 4 个人有成绩：

- 第 1 名：(4+1-1)×100÷4 = **100 分**
- 第 2 名：(4+1-2)×100÷4 = **75 分**
- 第 3 名：(4+1-3)×100÷4 = **50 分**
- 第 4 名：(4+1-4)×100÷4 = **25 分**

也就是将 100 分平均分成 4 份：[100, 75)、[75, 50)、[50, 25) 和 [25, 0)，积分则为排名对应那一份区间的上限。

如果这条赛道只有你 1 个人，你直接拿 100 分。分差随人数变化，人越多，相邻名次差得越少。

**总积分** = 你在所有赛道、所有版本上积分的总和。共 16 条赛道，单版本满分 1600 分，两个版本全拿第一就是 3200 分。

### 4. 为什么我没跑，积分也会变？

因为你的名次会被别人影响，而积分是按当前名次实时计算的，举两个例子：

- 人数不变的情况下，原来比你慢的人现在跑得比你快，你被往后挤，积分下降。相反，你超越了原先比你快的人，则积分上升；
- 排名不变的情况下（第一除外），人数变多，意味着新增的成绩都不如你，你的排名比例会提升，例如榜上有 4 个人时你是第 2 名，你的积分为 75 分，10 个人时还是第 2 名，积分就变成了 90 分。

总之，积分是浮动的，不是"跑一次就锁死"。

### 5. 会不会经常丢失成绩？

不会。上传失败的成绩会自动存在本地，保留 30 天，联网后自动补传，不用重跑。

如果你发现成绩没上榜，通常是网络问题，稍等一会、多跑一个有效圈或在围场页下拉刷新即可。
"""

    /**
     * 是否已读过当前版本指南。
     *
     * 返回 false = 从未读过或标记被清除（首次安装/清除数据）→ 应自动弹出。
     */
    fun isSeen(context: Context): Boolean {
        val version = readSeenVersion(context)
        Logger.i(TAG, "PaddockGuide.isSeen: stored=$version current=$GUIDE_VERSION")
        return version >= GUIDE_VERSION
    }

    /** 标记已读当前版本指南（写 filesDir flag）。 */
    fun markSeen(context: Context) {
        try {
            File(context.filesDir, GUIDE_FLAG_FILE).writeText(GUIDE_VERSION.toString())
            Logger.i(TAG, "PaddockGuide: marked seen v$GUIDE_VERSION")
        } catch (e: Throwable) {
            Logger.w(TAG, "PaddockGuide: markSeen write failed", e)
        }
    }

    /** 读取已读版本号；-1 = 从未读过。 */
    private fun readSeenVersion(context: Context): Int {
        return try {
            val file = File(context.filesDir, GUIDE_FLAG_FILE)
            if (file.exists()) file.readText().trim().toIntOrNull() ?: -1 else -1
        } catch (e: Throwable) {
            Logger.w(TAG, "PaddockGuide: read failed", e)
            -1
        }
    }
}
