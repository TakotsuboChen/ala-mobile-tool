package tools.alamobile.mod.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import tools.alamobile.mod.util.Logger

/**
 * 滑移率振感输出。三条互斥实现，由 [create] 按**权限 + 硬件能力**选一条。
 *
 * ## 一、十一轮迭代的证伪记录（改之前必读）
 *
 * | 尝试 | 假设 | 实测结果 |
 * |---|---|---|
 * | 幅度包络 + 颗粒（12/6/8/13 周期 = 23.4/11.7/15.4/25.0 Hz） | 调制频率决定质感 | **全部**判"糊" |
 * | 段长 16ms → 5ms | 时间分辨率不足 | 段长生效（dumpsys 实证 104×5ms），**仍然糊** |
 * | 每轮下发增量波形（n 常为 1） | 幅度跟随信号 | **嘎达嘎达**——每次 `vibrate()` 都独立起振 |
 * | 长窗 640ms + 160ms 滑动重发 | 长窗消除起振、滑动消除循环 | **诺基亚铃声**——6.25Hz 重发＝6.25Hz 调制 |
 *
 * **四条路的病根是同一个：只要周期性重发，重发频率本身就是一个听得见的调制。**
 *
 * - 16ms 重发 ⇒ 50Hz ⇒ 敲击（"嘎达"）
 * - 160ms 重发 ⇒ 6.25Hz ⇒ 脉冲串（"诺基亚铃声"）
 * - 人造波形内调制 ⇒ 11~25Hz ⇒ 波动（"呜——"/"糊"）
 *
 * 副产物：「延迟」也是窗口设计的必然代价。HAL 从新波形**段 0** 开始播，段 0 是
 * "窗口长度之前"的样本 ⇒ 正在播的样本年龄恒为 `窗口段数 × 段长`（640ms 窗 ⇒
 * 620ms 延迟）。用户原话「锁死/空转**前零点几秒**不怎么振」。
 *
 * ## 二、现在的设计：**平顶波形 + 两级判据**
 *
 * ```
 *   波形 = SEGMENTS 段**同一幅度**（平顶）+ repeat=0
 *   δ = |电平 − 上次下发|：
 *     δ < AMP_STEP(0.05)                        → 不重发（稳定期零重发）
 *     δ ≥ BIG_STEP(0.20)  （真事件）            → **立刻下发**，不受节流
 *     AMP_STEP ≤ δ < BIG_STEP（缓慢漂移）        → 按 TREND_MIN_MS(300ms) 节流
 *   任何两次之间 ≥ HARD_MIN_MS(50ms)
 * ```
 *
 * 三条推论：
 *
 * 1. **平顶波形没有周期**。用户否定的从来不是"周期"本身，而是**听得见的**周期。
 *    一条恒定幅度的波形由 LRA 平滑成**平稳的嗡**——没有起伏可听，`repeat = 0`
 *    回绕时也听不出来（回绕点是同一幅度，无台阶）。**这是"连续"的正解**：
 *    不靠长窗，靠"不变就不重发"。
 * 2. **两级判据把延迟挤到事件上**。这是关键：锁死/空转是**事件**（迟 100ms
 *    就废），推边是**趋势**（迟 200ms 无感）。单级限流会把两者同等对待 ⇒
 *    事件被拖 150ms，那正是「怎么还有延迟」。
 * 3. **零窗口延迟**。取最新电平直接下发，没有"窗口长度"这个延迟
 *    （见上文 640ms 窗的 620ms 台阶）。
 *
 * ### 重发的代价（为什么平顶波形不怕重发）
 *
 * 每次 `vibrate()` 都重新起振（AOSP 对同 token 在响振动先 `requestEnd`，
 * 新 conductor 从 `segmentIndex = 0` 起步，HAL 重新 on() + rampUp）。
 * 但**幅度台阶才是听的见的东西**：
 *
 * - 平顶 → 平顶（δ < AMP_STEP 时不发，或发了也是同幅值）：**无台阶、听不出来**。
 * - 平顶 A → 平顶 B：一次干净的电平跳变 = "强度变了"，这正是要反馈的信息。
 *
 * ### 为什么不用 `VibrationEffect.Composition` + PRIMITIVE_*
 *
 * 评估过：primitive 的语义与时长由 ROM 定义（本机 `dumpsys vibrator_manager`
 * 实测九个 primitive **全是 4ms**），`scale` 又是**逐 primitive** 的——本质是
 * "一串被缩放的孤立敲击"，正好回到离散脉冲老路。
 *
 * ## 三、三条实现路径
 *
 * | 路径 | 条件 | 手段 |
 * |---|---|---|
 * | [Envelope] | 有 VIBRATE **且** `hasAmplitudeControl()` | 上面的平顶波形 |
 * | [Duty] | 有 VIBRATE 但**无**振幅控制 | 强度改编码为**占空比** |
 * | [Fallback] | **无** VIBRATE | `performHapticFeedback` + 密集固定节奏 |
 *
 * 游戏 APK 自身没有 VIBRATE（`aapt2 dump permissions` 实测：官方版与共存版
 * manifest 均无），所以**官版用户永远走 [Fallback]**；共存版由
 * `coex-apk-builder` skill 阶段 4.7 补权限，走前两条。
 *
 * ## 四、持续输出必须显式停
 *
 * `createWaveform(..., repeat = 0)` 会**一直响下去**。电平归零时必须
 * `cancel()`（[tick] 在电平跌破 [MIN_TRIGGER_LEVEL] 时做），否则马达永远振动。
 * 另有一道不依赖回调链的硬兜底：[release]。
 *
 * ## 五、两条 driveline：状态同步 vs 事件流
 *
 * [Envelope] / [Duty] 是**状态同步**（电平稳定 = 空操作，波形自己循环）；
 * [Fallback] 是**事件流**（没有持续输出能力，只能按节奏反复叩击）。这个差异由
 * [continuous] 在基类里裁决——把两者混进一个语义，必然有一边是错的（都做成
 * 事件流会把连续路的 IPC 打爆；都做成状态同步会让免权限路只在跨档时响一下）。
 */
abstract class SlipHaptic internal constructor() {

    /** 上一次**实际下发**的时刻，0 = 未激活。 */
    private var lastOutputMs = 0L

    /** 上一次真正下发的幅度（0..1）。仅 [hasState] 为真时有意义。 */
    private var lastLevel = 0f

    /** 是否已经下发过。用独立布尔而非哨兵值——哨兵要与合法值域不重叠才安全，
     *  而合法的电平包括 0，用 NaN 之类还会踩 `NaN != NaN` 的坑。 */
    private var hasState = false

    /** 当前是否已有持续输出（或事件流正在叩击）。 */
    private var outputActive = false

    /** 上一次**打过日志**的档位（日志去重用）。 */
    private var lastLoggedBand = NO_BAND

    /** 本实现是否具备"持续输出"能力——见类注释末尾「两条 driveline」。 */
    protected open val continuous: Boolean get() = true

    /**
     * 按当前电平驱动振感。由主线程轮询调用（[SlipFeedbackView]）。
     *
     * @param level 0..1 反馈电平（与视觉同源）
     * @param nowMs `SystemClock.uptimeMillis()`，由调用方传入以复用时钟
     */
    fun tick(level: Float, nowMs: Long) {
        val l = level.coerceIn(0f, 1f)

        if (l < MIN_TRIGGER_LEVEL) {
            if (outputActive) {
                outputActive = false
                hasState = false
                lastLoggedBand = NO_BAND
                stopOutput()
                Logger.i(TAG, "SlipHaptic: 电平归零，停止输出 path=$pathTag")
            }
            // 复位下发时刻：下次起振立刻可发，不残留等待窗口。
            lastOutputMs = 0L
            return
        }

        val band = bandOf(l)

        if (!continuous) {
            // 事件流：每次都交给子类叩击，节奏由子类内部节流。
            outputActive = true
            fire(l, band)
            logBandChange(l, band)
            return
        }

        // ── 状态同步：**两级判据**（见类注释「二」）──────────────────────
        // 把"信号突变"与"缓慢漂移"分开处理，是因为它们对延迟的敏感度差几个
        // 数量级：锁死是**事件**（迟 100ms 就废），推边是**趋势**（迟 200ms 无感）。
        val delta = if (hasState) kotlin.math.abs(l - lastLevel) else 1f

        if (delta < AMP_STEP) return                      // 没变 ⇒ 一次都不发

        val big = delta >= BIG_STEP                       // 真事件（锁死/空转/松脱）
        if (lastOutputMs != 0L) {
            val dt = nowMs - lastOutputMs
            if (dt < HARD_MIN_MS) return                  // 硬下限：防两值间来回跳
            // 大跳变**立刻下发**；小变化按 TREND_MIN_MS 节流（跟踪趋势足够）。
            if (!big && dt < TREND_MIN_MS) return
        }

        lastOutputMs = nowMs
        lastLevel = l
        hasState = true
        outputActive = true
        fire(l, band)
        logBandChange(l, band)
    }

    private fun logBandChange(level: Float, band: Int) {
        if (band == lastLoggedBand) return
        lastLoggedBand = band
        Logger.i(
            TAG,
            "SlipHaptic: level=%.2f band=%d/%d path=%s".format(level, band, BANDS, pathTag)
        )
    }

    /**
     * 注入 [Fallback] 的 View 载体（前两条路径忽略）。编辑模式/配置重建会换
     * view，故用 setter 而非构造参数——调用方每次重建后重新注入即可。
     */
    open fun attachView(view: View?) = Unit

    /** 实际下发一次输出。level ∈ [0,1] 已 clamp，band 是其日志档位。 */
    protected abstract fun fire(level: Float, band: Int)

    /** 停机（仅持续输出路径需要）。 */
    protected open fun stopOutput() = Unit

    /** 日志用的路径标识（"env" / "duty" / "hfc"）。 */
    protected abstract val pathTag: String

    /**
     * 彻底释放：停止一切输出且不再接受 [tick]。
     *
     * ⚠️ **overlay 拆除路径必须调它**。无限循环波形只把引用置 null 不会让马达
     * 停——用户关掉开关或折叠控件后马达会一直响。虽然 view 的
     * `onDetachedFromWindow` 也会把电平归零再回调一次（正常路径已能停机），
     * 但这里必须有一道**不依赖"回调链恰好走通"**的硬兜底：root 为 null 的中止
     * 路径、view 从未 attach 过的路径都绕过了它。
     */
    open fun release() {
        outputActive = false
        hasState = false
        lastOutputMs = 0L
        stopOutput()
    }

    companion object {
        private const val TAG = "AlaMobileTool"

        /** 无档位哨兵（日志去重用）。 */
        private const val NO_BAND = -1

        /** 日志用档数（16 档 ⇒ 相邻 6.25%）。**只用于日志**——重发判据是
         *  [AMP_STEP] 的滞回，与这个划分无关。 */
        private const val BANDS = 16

        /**
         * **跟踪阈值**：幅度变化不足这个量就不重发（缓慢漂移的死区）。
         *
         * ⚠️ 这条是"连续"的来源——稳定行驶时电平抖不到 5% ⇒ **一次重发都没有**
         * ⇒ 没有起振瞬态、没有周期性调制。调小会让抖动也触发重发。
         */
        private const val AMP_STEP = 0.05f

        /**
         * **事件阈值**：幅度变化达到这个量视为"信号突变"，**立刻下发、不受节流**。
         *
         * 0.20 对应"用尽抓地力 → 开始打滑"这一档跨度（映射曲线的 0.25 档）。
         * 锁死/空转/突然松脱都属于这一类，而它们**迟 100ms 就失去意义**——
         * 这是"延迟"抱怨的直接解药（见 [TREND_MIN_MS] 的对比）。
         */
        private const val BIG_STEP = 0.20f

        /**
         * **跟踪节流**：小变化（[AMP_STEP] ≤ δ < [BIG_STEP]）之间的最短间隔。
         *
         * 缓慢推边（换挡、慢慢给油）用 300ms 的台阶跟踪就够了——趋势的变化速率
         * 本身就慢，迟 200ms 主观无感；而**快速**变化走 [BIG_STEP] 的直通车。
         */
        private const val TREND_MIN_MS = 300L

        /**
         * **硬下限**：任何两次下发之间的绝对最短间隔（20Hz）。
         *
         * 只用于挡住"电平在两个大值之间来回跳"（会连续命中 [BIG_STEP]）把 IPC
         * 打到 60Hz。正常驾驶永远命中不到——事件级变化不会 50ms 内来回跳。
         */
        private const val HARD_MIN_MS = 50L

        /** 低于此电平完全不出力（native 侧起振点之下就是静默区，双保险）。 */
        private const val MIN_TRIGGER_LEVEL = 0.05f

        /** [Fallback] 的固定叩击间隔：≈31Hz，**越过"脉动"感知上限**（~10Hz）。 */
        private const val FALLBACK_INTERVAL_MS = 22L

        /** 把电平量化到 [BANDS] 档（0 .. BANDS-1），仅用于日志。 */
        internal fun bandOf(level: Float): Int =
            (level.coerceIn(0f, 1f) * BANDS).toInt().coerceIn(0, BANDS - 1)

        /**
         * 单个波形段时长。**必须 ≥ HAL 的 `rampStepDurationMs`**（本机 5ms，
         * `dumpsys vibrator_manager` 实证），否则段长被量化吃掉。
         *
         * 取 5ms（= 一个 ramp step）⇒ 波形在 HAL 里是"斜坡-台地-斜坡"的
         * 最细粒度形状；因为整段同值，实际输出是**平稳的直线**。
         */
        private const val SEGMENT_MS = 5L

        /**
         * 段数。96 × 5ms = 480ms。因为整段同值，循环回绕处没有台阶——
         * 长度只影响"万一某次轮询迟到，旧波形还能撑多久"，480ms 绰绰有余。
         */
        private const val SEGMENTS = 96

        /** 段时长数组（复用，避免每次 [fire] 分配）。 */
        private val SEGMENT_TIMINGS = LongArray(SEGMENTS) { SEGMENT_MS }

        /**
         * 工厂：按设备权限/能力三选一。
         *
         * ⚠️ 权限判定是**必须**的，不是保险：模块运行在游戏进程，权限来自游戏
         * APK 的 manifest。官版游戏没有 VIBRATE，此时 `vibrate()` 会抛
         * `SecurityException`（`vibrateWithPermissionCheck` 内部
         * `enforceCallingOrSelfPermission`）。
         */
        fun create(context: Context, maxIntensityPct: Int): SlipHaptic {
            val hasPermission = context.checkSelfPermission(Manifest.permission.VIBRATE) ==
                PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                Logger.i(TAG, "SlipHaptic: 无 VIBRATE 权限 → 走免权限 HapticFeedbackConstants 路")
                return Fallback()
            }
            val v = runCatching {
                val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                        ?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vibrator != null && vibrator.hasVibrator()) vibrator else null
            }.getOrNull()
            if (v == null) {
                Logger.i(TAG, "SlipHaptic: 无可用 Vibrator → 走免权限 HapticFeedbackConstants 路")
                return Fallback()
            }
            val hasAmplitude = runCatching { v.hasAmplitudeControl() }.getOrDefault(false)
            Logger.i(TAG, "SlipHaptic: 真振幅路 hasAmplitudeControl=$hasAmplitude")
            return if (hasAmplitude) Envelope(v, maxIntensityPct) else Duty(v, maxIntensityPct)
        }

        /** 把电平映射成载波幅度（0..255）。0 就是真静音。 */
        private fun carrierFor(level: Float, maxIntensityPct: Int): Int =
            ((maxIntensityPct.coerceIn(0, 100) / 100f) * level.coerceIn(0f, 1f) * 255f)
                .toInt().coerceIn(0, 255)

        /** 平顶幅度数组（[Envelope] 用）：所有段同一个值。 */
        private fun buildFlat(carrier: Int): IntArray = IntArray(SEGMENTS) { carrier }

        /** 占空比数组（[Duty] 用）：`onCount` 个段通电，其余 0。 */
        private fun buildDuty(onCount: Int): IntArray = IntArray(SEGMENTS) { i ->
            // 均匀铺开：第 i 段是否通电，由"前 i+1 段累计应含多少个 on"决定
            //（Bresenham 式整数累积，避免浮点误差攒成周期性缺口）。
            if ((i + 1) * onCount / SEGMENTS > i * onCount / SEGMENTS) 255 else 0
        }
    }

    /**
     * 两条真振幅路的公共底座：持有 [Vibrator]、封装下发与停机。
     *
     * ⚠️ 只有**构造函数**是 `internal`：外部拿不到构造入口（唯一入口是
     * [Companion.create] 在确认 VIBRATE 权限之后调用）。**这同时是
     * `@SuppressLint("MissingPermission")` 正确性的边界**——豁免之所以成立，
     * 就是因为构造这条路只可能在权限已校验之后走通。子类保持 public 是为了让
     * [SlipHaptic] 基类型之外仍有具名类型可用，而基类若为 internal 会触发
     * 「public 子类暴露 internal 超类型」编译错误。
     */
    abstract class AmplitudeHaptic internal constructor(
        protected val vibrator: Vibrator,
        private val maxIntensityPct: Int,
    ) : SlipHaptic() {

        override fun stopOutput() = cancel()

        override fun fire(level: Float, band: Int) {
            vibrate(VibrationEffect.createWaveform(SEGMENT_TIMINGS, buildAmps(level), REPEAT_FOREVER))
        }

        /** 生成幅度数组（子类实现具体编码）。 */
        protected abstract fun buildAmps(level: Float): IntArray

        protected fun peakCarrier(level: Float): Int = carrierFor(level, maxIntensityPct)

        @SuppressLint("MissingPermission")   // 见上：构造前已由 create() 校验权限
        protected fun vibrate(effect: VibrationEffect) {
            runCatching { vibrator.vibrate(effect) }.onFailure {
                Logger.w(TAG, "SlipHaptic: vibrate 失败 path=$pathTag", it)
            }
        }

        @SuppressLint("MissingPermission")
        private fun cancel() {
            runCatching { vibrator.cancel() }.onFailure {
                Logger.w(TAG, "SlipHaptic: cancel 失败", it)
            }
        }

        private companion object {
            /**
             * `createWaveform(timings, amplitudes, repeat)` 的 `repeat = 0` 是
             * **从头无限循环**（"不循环"是 `repeat = -1`）。
             *
             * ⚠️ 平顶波形**循环也听不出来**（回绕点是同一幅度、无台阶）——
             * 这正是"不靠重发也能连续"的根据。
             */
            const val REPEAT_FOREVER = 0
        }
    }

    /**
     * 路径 A：**平顶幅度**（有振幅控制）。见类注释「二」。
     */
    class Envelope internal constructor(
        vibrator: Vibrator,
        maxIntensityPct: Int,
    ) : AmplitudeHaptic(vibrator, maxIntensityPct) {

        override val pathTag = "env"

        override fun buildAmps(level: Float): IntArray = buildFlat(peakCarrier(level))
    }

    /**
     * 路径 B：**占空比**（有 VIBRATE 但**无**振幅控制）。
     *
     * 这类设备把任何非零振幅一律按满幅播放，强度只能靠"每个周期通多久"表达。
     *
     * ⚠️ **占空比下限 50%**：`on` 段若稀到每 4 段才 1 段（12.5Hz 斩波），又回到
     * 用户否定的「哒哒哒」。取 50% 起 ⇒ 最稀也是 100Hz 交替（5ms 段），听感是
     * "粗糙"而非"点状"。代价是只有 2× 动态范围（50%~100%）——够用。
     */
    class Duty internal constructor(
        vibrator: Vibrator,
        private val maxIntensityPct: Int,
    ) : AmplitudeHaptic(vibrator, maxIntensityPct) {

        override val pathTag = "duty"

        override fun buildAmps(level: Float): IntArray = buildDuty(onCountFor(level))

        /** 通电段数 ∈ [SEGMENTS/2, SEGMENTS]。 */
        private fun onCountFor(level: Float): Int {
            val strength = (maxIntensityPct.coerceIn(0, 100) / 100f) * level.coerceIn(0f, 1f)
            val duty = 0.5f + 0.5f * strength          // ∈ [0.5, 1]
            return (duty * SEGMENTS).toInt().coerceIn(SEGMENTS / 2, SEGMENTS)
        }
    }

    /**
     * 路径 C：**免权限**（[View.performHapticFeedback]）。
     *
     * 官方文档明确「using HapticFeedbackConstants with a View doesn't require
     * the VIBRATE permission」。代价是没有任何幅度/波形控制，只能"敲一下子"，
     * 所以这条路**做不到真正的绵密**——只能在节奏上尽量贴近：固定
     * [FALLBACK_INTERVAL_MS]（≈31Hz）反复叩击，强度维度退化为三档常量选择。
     */
    class Fallback : SlipHaptic() {

        private companion object {
            /** 强度档 → 候选常量（每档两个，按 ROM 差异取 SDK 支持的第一个）。 */
            val LEVEL_CANDIDATES: Array<IntArray> = arrayOf(
                // 最弱：极轻的"滴答"。
                intArrayOf(
                    HapticFeedbackConstants.CLOCK_TICK,
                    if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS
                    else HapticFeedbackConstants.CLOCK_TICK,
                ),
                // 中：键盘/虚拟键那一档，LRA 上是清晰的"嗒"。
                intArrayOf(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.VIRTUAL_KEY_RELEASE
                    else HapticFeedbackConstants.VIRTUAL_KEY,
                ),
                // 最强：确认 / 长按档。
                intArrayOf(
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.LONG_PRESS,
                    if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
                    else HapticFeedbackConstants.LONG_PRESS,
                ),
            )
        }

        private var view: View? = null

        /** 上一次叩击时刻（这条路是事件流而非持续输出，故单独计时）。 */
        private var lastTapMs = 0L

        // 没有持续输出能力：只能按节奏反复叩击（见基类 continuous 的说明）。
        override val continuous: Boolean get() = false

        override val pathTag = "hfc"

        override fun attachView(view: View?) {
            this.view = view
        }

        override fun fire(level: Float, band: Int) {
            val v = view ?: return
            val now = SystemClock.uptimeMillis()
            if (lastTapMs != 0L && now - lastTapMs < FALLBACK_INTERVAL_MS) return
            lastTapMs = now

            val idx = (level * 3f).toInt().coerceIn(0, 2)
            val constant = LEVEL_CANDIDATES[idx].firstOrNull { it != 0 }
                ?: HapticFeedbackConstants.VIRTUAL_KEY
            runCatching {
                v.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            }
        }

        override fun stopOutput() {
            // 免权限路是离散叩击，没有"持续输出"要停，重置计时即可。
            lastTapMs = 0L
        }
    }
}
