package tools.alamobile.mod.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import tools.alamobile.mod.util.Logger

/**
 * **抓地力反馈 + 路肩振感 的合并输出引擎**。
 *
 * ## 一、为什么必须合并成一条波形（这是本类存在的唯一理由）
 *
 * 两条反馈各自都是一个**持续振动**。真振幅路下发的是
 * `createWaveform(timings, amps, repeat = 0)` —— **无限循环**。AOSP 对同一
 * token 的规则是：新 `vibrate()` 先 `requestEnd` 掉正在响的那条，再从新波形的
 * 段 0 起振。所以**两条各自维护自己的波形，谁后发谁赢**：
 *
 * - 先锁死（抓地力满振）后压路肩 → 路肩后发，抓地力的振动**消失**；
 * - 先压路肩后锁死 → 抓地力后发，路肩的**消失**。
 *
 * 而用户规格明确要求「在路肩上打滑时，要求能同时感受到两者正确的振感」。
 * 唯一出路是**把两条电平合成到一条波形的每一个段里**：
 *
 * ```
 *   amp[i] = max( slipAmp , kerbAmp × grain[i] )      i = 0 .. SEGMENTS-1
 * ```
 *
 * 于是"路肩关"的那些段仍带着抓地力电平 ⇒ 打滑感连续；"路肩开"的段被抬到
 * 路肩电平 ⇒ 路肩的颗粒感叠加在上面。两条反馈在**同一台马达、同一条波形**
 * 里同时存在，不存在谁覆盖谁。
 *
 * ⚠️ **绝不要**退回"两条各发各的"。那条路的表现是"开着路肩振感就感觉不到
 * 抓地力了"（或反之），而且取决于两条电平谁先跳变——不可预测。
 *
 * ## 二、路肩的"颗粒感 + 频率随速度"怎么做的
 *
 * 路肩的物理模型是**一系列离散撞击**（振动带的凸起），所以它的波形是
 * **周期性颗粒**而不是平顶：
 *
 * ```
 *   cycles  = round(rateHz × SEGMENTS × SEGMENT_MS / 1000)   // 波形内几个颗粒周期
 *   period  = SEGMENTS / cycles                               // 每周期几段
 *   onSegs  = max(1, period / 2)                              // 每周期通电几段
 *   grain[i]= 该段是否通电（Bresenham 均匀铺开，同 Duty 路）
 * ```
 *
 * `rateHz` 由 **native 直接复现游戏自己的路肩音高逻辑**给出
 * （`kerb_haptic.c`：`pitch = clamp(|carSpeed|/20, kerbMinPitch, kerbMaxPitch)`，
 * 与 `IRDSSoundController.kerbSoundUpdate` 0x1A7881C 逐指令一致；颗粒率 =
 * 片段基频 × pitch）⇒ **颗粒率随车速变化的曲线形状完全由游戏决定**，模块不
 * 维护任何数值。
 *
 * ### ⚠️ 为什么不在每次车速变化时重发波形
 *
 * 每次 `vibrate()` 都是一次**独立起振**（`requestEnd` → HAL `on()` + ramp-up）。
 * 重发频率本身就是一个听得见的调制——这是十二轮迭代用血换来的结论（见下方证伪表）。
 * 所以本类**不按原始 Hz 重发**，而是把颗粒率量化成整数段周期
 * （见 [buildGrain]），只在**跨过量化边界**时才重发（视为事件、不受节流）。
 * 颗粒率连续变化时，跨边界是离散的、稀疏的 ⇒ 不产生周期性调制。
 *
 * ### ⚠️ "短脉冲 + 长静默"是"哒哒哒"的唯一正解（[buildGrain]）
 *
 * 首版做成 **50% 占空方波** ⇒ 静默只有半个周期（20~50Hz 时 10~25ms）⇒
 * LRA 来不及回落，两次脉冲在机械上连成一体 ⇒ 低幅连续振动 = 用户报的
 * 「松松散散的很软」「跟转子马达一样」。
 *
 * 正解是 **15ms 脉冲 + 25~45ms 静默**（周期 40~60ms = 16.7~25 Hz，落在
 * 触觉 flutter 区）：脉冲之间的静默 ≥15ms 才会被感知为**独立的一次敲击**
 * （Ng et al. 2021），而 <60Hz 才不会退化成连续 hum。两个条件同时满足，
 * 才出来「哒、哒、哒」。详见 [PULSE_SEGS] / [PERIOD_MIN_SEGS]。
 *
 * ## 三、抓地力侧的完整证伪记录（**改之前必读**）
 *
 * | 尝试 | 假设 | 实测结果 |
 * |---|---|---|
 * | 幅度包络 + 颗粒（12/6/8/13 周期 = 23.4/11.7/15.4/25.0 Hz） | 调制频率决定质感 | **全部**判"糊" |
 * | 段长 16ms → 5ms | 时间分辨率不足 | 段长生效（dumpsys 实证 104×5ms），**仍然糊** |
 * | 每轮下发增量波形（n 常为 1） | 幅度跟随信号 | **嘎达嘎达**——每次 `vibrate()` 都独立起振 |
 * | 长窗 640ms + 160ms 滑动重发 | 长窗消除起振、滑动消除循环 | **诺基亚铃声**——6.25Hz 重发＝6.25Hz 调制 |
 *
 * **四条路的病根是同一个：只要周期性重发，重发频率本身就是一个听得见的调制。**
 * 副产物：「延迟」也是窗口设计的必然代价（640ms 窗 ⇒ 段 0 是 620ms 前的样本）。
 *
 * ## 四、现在的设计：**平顶波形 + 两级判据**（抓地力侧）
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
 * 2. **两级判据把延迟挤到事件上**。锁死/空转是**事件**（迟 100ms 就废），
 *    推边是**趋势**（迟 200ms 无感）。单级限流会把两者同等对待。
 * 3. **零窗口延迟**。取最新电平直接下发，没有"窗口长度"这个延迟。
 *
 * 路肩侧的重发判据**并入同一套**：颗粒的出现/消失（`grainOn` 翻转）视为
 * **事件**（立刻下发，不受节流），其余跟抓地力共用 δ 判据。这样"压上路肩"
 * 是即时的，而路肩期间的强度随车速缓慢漂移走趋势节流。
 *
 * ## 五、三条实现路径
 *
 * | 路径 | 条件 | 手段 |
 * |---|---|---|
 * | [Envelope] | 有 VIBRATE **且** `hasAmplitudeControl()` | 逐段取大的混合波形 |
 * | [Duty] | 有 VIBRATE 但**无**振幅控制 | 无振幅维度：路肩出纯颗粒、抓地力出占空比 |
 * | [Fallback] | **无** VIBRATE | `performHapticFeedback` + 按颗粒率叩击 |
 *
 * 游戏 APK 自身没有 VIBRATE（`aapt2 dump permissions` 实测：官方版与共存版
 * manifest 均无），所以**官版用户永远走 [Fallback]**；共存版由
 * `co-apk-builder` skill 阶段 4.7 补权限，走前两条。
 *
 * ## 六、持续输出必须显式停
 *
 * `createWaveform(..., repeat = 0)` 会**一直响下去**。电平归零时必须
 * `cancel()`（[tick] 在峰值跌破 [MIN_TRIGGER_LEVEL] 时做），否则马达永远振动。
 * 另有一道不依赖回调链的硬兜底：[release]。
 */
abstract class HapticMixer internal constructor() {

    /** 上一次**实际下发**的时刻，0 = 未激活。 */
    private var lastOutputMs = 0L

    /** 上一次真正下发的**峰值电平**（0..1）。仅 [hasState] 为真时有意义。 */
    private var lastLevel = 0f

    /** 上一次下发时是否带路肩颗粒。用于识别"压上/离开路肩"这个事件。 */
    private var lastGrainOn = false

    /**
     * 上一次下发时**量化后的颗粒周期**（段数，0 = 无路肩）。
     *
     * ⚠️ 跟踪的是**量化后的周期**而不是原始 Hz：颗粒率随车速连续变化，
     * 但波形里能表达的只有整数段周期（见 [buildGrain]）。只有跨过量化边界时
     * 重发才有意义——否则每次车速微动都重发，重发频率本身就成了听得见的调制。
     */
    private var lastGrainPeriod = 0

    /** 是否已经下发过。用独立布尔而非哨兵值——合法的电平包括 0。 */
    private var hasState = false

    /** 当前是否已有持续输出（或事件流正在叩击）。 */
    private var outputActive = false

    /** 上一次**打过日志**的档位（日志去重用）。 */
    private var lastLoggedBand = NO_BAND

    /** 本实现是否具备"持续输出"能力——见类注释「六」。 */
    protected open val continuous: Boolean get() = true

    /**
     * 按当前两条电平驱动振感。由主线程轮询调用（[SlipFeedbackView]）。
     *
     * @param slip      抓地力反馈电平 0..1
     * @param kerb      路肩电平 0..1
     * @param kerbRateHz 路肩目标颗粒率（Hz），未压路肩时 0
     * @param nowMs     `SystemClock.uptimeMillis()`，由调用方传入以复用时钟
     */
    fun tick(slip: Float, kerb: Float, kerbRateHz: Float, nowMs: Long) {
        val s = slip.coerceIn(0f, 1f)
        val k = kerb.coerceIn(0f, 1f)
        val rate = if (kerbRateHz.isFinite() && kerbRateHz > 0f) kerbRateHz else 0f

        // 峰值电平决定"要不要出声"；颗粒有无决定"波形形状"。
        //
        // ⚠️ **路肩在时忽略抓地力**（用户规格：路肩优先）。否则抓地力的连续
        // 载波会淹没路肩脉冲（实机报「抓地力振感会淹没路肩振感」）。这条在
        // tick 层就掐掉，`fire` 里再掐一道（双保险：`fire` 可能被其它路径调用）。
        val peak = if (k >= MIN_TRIGGER_LEVEL) k else s
        val grainOn = k >= MIN_TRIGGER_LEVEL

        if (peak < MIN_TRIGGER_LEVEL) {
            if (outputActive) {
                outputActive = false
                hasState = false
                lastGrainOn = false
                lastGrainPeriod = 0
                lastLoggedBand = NO_BAND
                stopOutput()
                Logger.i(TAG, "HapticMixer: 电平归零，停止输出 path=$pathTag")
            }
            // ⚠️ 归零时子类需要清各自的待办（如路肩桥接颗粒），见 [onLevelZero]。
            onLevelZero()
            // 复位下发时刻：下次起振立刻可发，不残留等待窗口。
            lastOutputMs = 0L
            return
        }

        val band = bandOf(peak)

        // 事件流有两条来源：
        // ① [continuous] 为假的实现（[Fallback] 无持续输出能力）；
        // ② **路肩在时**——走 HAL 的 primitive 重发（见 [Envelope.fire]），
        //    节奏由子类按颗粒周期节流，不受下面的两级判据约束。
        if (!continuous || grainOn) {
            // ⚠️ **必须同步 `lastLevel` / `lastGrainOn` / `lastOutputMs`**。
            // 路肩期间本分支直接 return，状态同步分支从不执行 ⇒ 若这里不更新，
            // 路肩结束后第一帧的 `delta = |slip − lastLevel|` 会拿"路肩前最后一次
            // 抓地力电平"作基准算出一个巨大的值（例如 |1.0 − 0.0| = 1.0），
            // 于是每次都被判成"大跳变"……但同时 `lastOutputMs` 也停留在路肩前，
            // 若路肩很短，`dt < HARD_MIN_MS` 会把它挡掉，之后 `hasState` 与
            // `lastGrainOn` 又错位，抓地力**再也恢复不了**（用户实测：飞出路肩后
            // 继续画甜甜圈，抓地力振感居然没了）。
            lastOutputMs = nowMs
            lastLevel = peak
            lastGrainOn = grainOn
            lastGrainPeriod = if (grainOn) grainPeriodOf(rate) else 0
            hasState = true
            outputActive = true
            fire(s, k, rate, grainOn)
            logBandChange(peak, band)
            return
        }

        // ── 状态同步：**两级判据**（见类注释「四」）──────────────────────
        val delta = if (hasState) kotlin.math.abs(peak - lastLevel) else 1f
        val grainChanged = grainOn != lastGrainOn
        // 颗粒率随车速连续变化（游戏自己的音高逻辑），但波形只能表达整数段
        // 周期 ⇒ 只在**跨过量化边界**时算事件。没跨边界就不重发，避免"车速微动
        // 就重发"把重发频率变成听得见的调制（历史教训见类注释「二」）。
        val grainPeriod = if (grainOn) grainPeriodOf(rate) else 0
        val periodChanged = grainOn && lastGrainOn && grainPeriod != lastGrainPeriod

        // 颗粒出现/消失、颗粒率换档本身就是事件（必须立刻反馈）；幅度没变也发。
        if (!grainChanged && !periodChanged && delta < AMP_STEP) return

        val big = periodChanged || delta >= BIG_STEP
        // ⚠️ **颗粒消失（离开路肩 → 抓地力恢复）不受任何节流**（2026-10-04 实机修复）。
        // 到达本分支时必然 `!grainOn`（路肩在时上面的事件流分支已 return），所以
        // 这里的 `grainChanged` 只可能是 `true → false` = 抓地力要接管的那一帧。
        // 而路肩期间上面的事件流分支**逐帧刷新** `lastOutputMs`，于是这一帧的
        // `dt ≈ 16ms < HARD_MIN_MS(50ms)` 会被连挡 3 帧（实测日志：离场后 +9ms
        // 电平归零、**+699ms** 才恢复 = 用户报的「明显延迟」）。恢复是**事件**
        // （同锁死/空转，迟一帧就断档），必须立刻下发。
        if (grainChanged) {
            lastOutputMs = nowMs
            lastLevel = peak
            lastGrainOn = grainOn
            lastGrainPeriod = grainPeriod
            hasState = true
            outputActive = true
            fire(s, k, rate, grainOn)
            logBandChange(peak, band)
            return
        }
        if (lastOutputMs != 0L) {
            val dt = nowMs - lastOutputMs
            if (dt < HARD_MIN_MS) return                  // 硬下限：防两值间来回跳
            // 大跳变**立刻下发**；小变化按 TREND_MIN_MS 节流（跟踪趋势足够）。
            if (!big && dt < TREND_MIN_MS) return
        }

        lastOutputMs = nowMs
        lastLevel = peak
        lastGrainOn = grainOn
        lastGrainPeriod = grainPeriod
        hasState = true
        outputActive = true
        fire(s, k, rate, grainOn)
        logBandChange(peak, band)
    }

    private fun logBandChange(level: Float, band: Int) {
        if (band == lastLoggedBand) return
        lastLoggedBand = band
        Logger.i(
            TAG,
            "HapticMixer: level=%.2f band=%d/%d path=%s".format(level, band, BANDS, pathTag)
        )
    }

    /**
     * 注入 [Fallback] 的 View 载体（前两条路径忽略）。编辑模式/配置重建会换
     * view，故用 setter 而非构造参数——调用方每次重建后重新注入即可。
     */
    open fun attachView(view: View?) = Unit

    /**
     * 实际下发一次输出。
     *
     * @param slip   抓地力电平 0..1（已 clamp）
     * @param kerb   路肩电平 0..1（已 clamp）
     * @param rateHz 路肩颗粒率（Hz），0 = 无路肩
     * @param grainOn 本帧是否带路肩颗粒
     */
    protected abstract fun fire(slip: Float, kerb: Float, rateHz: Float, grainOn: Boolean)

    /** 停机（仅持续输出路径需要）。 */
    protected open fun stopOutput() = Unit

    /**
     * 电平归零（[tick] 峰值跌破 [MIN_TRIGGER_LEVEL]）时回调——子类在此清各自的
     * 待办状态。见 [Envelope] 的路肩桥接颗粒（陈旧的待办会在几秒后误补发）。
     */
    protected open fun onLevelZero() = Unit

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
        lastGrainOn = false
        lastGrainPeriod = 0
        lastOutputMs = 0L
        stopOutput()
    }

    companion object {
        private const val TAG = "AlaMobileTool"

        /** 无档位哨兵（日志去重用）。 */
        private const val NO_BAND = -1

        /** 日志用档数（16 档 ⇒ 相邻 6.25%）。**只用于日志**。 */
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
         * 锁死/空转/突然松脱都属于这一类，而它们**迟 100ms 就失去意义**。
         */
        private const val BIG_STEP = 0.20f

        /**
         * **跟踪节流**：小变化（[AMP_STEP] ≤ δ < [BIG_STEP]）之间的最短间隔。
         *
         * 缓慢推边（换挡、慢慢给油、路肩上缓加速）用 300ms 的台阶跟踪就够了。
         */
        private const val TREND_MIN_MS = 300L

        /**
         * **硬下限**：任何两次下发之间的绝对最短间隔（20Hz）。
         *
         * 只用于挡住"电平在两个大值之间来回跳"把 IPC 打到 60Hz。
         */
        private const val HARD_MIN_MS = 50L

        /** 低于此电平完全不出力（native 侧起振点之下就是静默区，双保险）。 */
        private const val MIN_TRIGGER_LEVEL = 0.05f

        /**
         * 路肩通道**固定满幅**（2026-10-05 删除独立强度滑条后）。
         *
         * `KERB_FULL_PCT` 用于 [carrier]（waveform 兜底路的幅度）；
         * `KERB_FULL_SCALE` 用于 primitive 路的 `scale` 参数。
         * 两者都是满值——不再有用户可调维度（primitive HAL 忽略 scale，
         * 滑条空转，故删除）。
         */
        private const val KERB_FULL_PCT = 100
        private const val KERB_FULL_SCALE = 1f

        /**
         * [Fallback] **抓地力**通道的叩击间隔（由独立 ticker 驱动，见 [Fallback.gripTicker]）。
         *
         * ## ⚠️ 这个数字决定"绵密"还是"哒哒哒"，是官版手感的唯一旋钮
         *
         * 官版（`com.Vince`）没有 VIBRATE 权限 ⇒ 走 [Fallback]（`performHapticFeedback`
         * 免权限路），**无法下发自定义波形**——能传 `VibrationEffect` 的 `vibrate()`
         * 在服务端 `VibratorManagerService.vibrateWithPermissionCheck` 里被
         * `enforceCallingOrSelfPermission("android.permission.VIBRATE", "vibrate")`
         * 拦下（设备 services.jar smali 实证），而免权限的 `performHapticFeedback`
         * 只接受 **int 语义常量**、effect 由系统预置表生成（客户端插不进字节）。
         * ⇒ 共存版那种 `createWaveform(96×5ms, repeat = 0)` 的平顶无限循环波形
         * 官版**物理上做不到**，只能用高频叩击逼近"LRA 持续通电"。
         *
         * ## 判据：官方的振铃窗口
         *
         * Android *Haptics design principles* 明文：一次 10~20ms 的输入之后，
         * **执行器还会继续振铃 20~50ms**。⇒ 只要叩击间隔落在这个振铃窗口内，
         * 下一次叩击就赶在马达回落之前 ⇒ 机械上连续 ⇒ 听感绵密。
         * 反之间隔 > 振铃时长（原值 22ms 叠 16ms 轮询量化后 ≈32ms）就会
         * "敲一下、停一下"⇒ **"类似路肩的哒哒哒"**（用户 2026-10-05 实机反馈）。
         *
         * ## ⚠️ 必须由独立 ticker 驱动，不能只靠 60Hz 轮询
         *
         * [fire] 只被 [SlipFeedbackView] 的 16ms 轮询调用 ⇒ 叩击率被**硬钳在
         * 62.5Hz**：常量写多小都没用，两次 `fire` 之间本来就隔 16ms。所以抓地力
         * 改由 [Fallback.gripTicker]（100Hz）独立驱动——本常量才真正生效。
         *
         * ⚠️ 与 [FALLBACK_KERB_MIN_INTERVAL_MS] **绝不能合并**：两者诉求相反
         * （抓地力要连续、路肩要离散），共用一个常量会让其中一条必然做错——
         * 这正是 2026-10-05 那个 bug 的结构性成因（抓地力被动继承了路肩的节奏）。
         */
        private const val FALLBACK_GRIP_INTERVAL_MS = 10L

        /**
         * [Fallback] **路肩**通道的叩击间隔下限。
         *
         * 路肩要的就是**离散撞击**（"哒哒哒"是它的设计目标，不是 bug），故下限
         * 必须 ≥22ms（≈45Hz，flutter 区，见类注释「二」）。
         *
         * ⚠️ **绝不能与 [FALLBACK_GRIP_INTERVAL_MS] 合并**：两者诉求相反（抓地力
         * 要连续、路肩要离散），共用一个常量会让其中一条必然做错——这正是
         * 2026-10-05 那个 bug 的结构性成因（抓地力被动继承了路肩的节奏）。
         */
        private const val FALLBACK_KERB_MIN_INTERVAL_MS = 22L

        /**
         * 单个波形段时长。**必须 ≥ HAL 的 `rampStepDurationMs`**（本机 5ms，
         * `dumpsys vibrator_manager` 实证），否则段长被量化吃掉。
         */
        private const val SEGMENT_MS = 5L

        /**
         * 段数。96 × 5ms = 480ms。颗粒周期的上限受它约束（见 [buildGrain]）。
         */
        private const val SEGMENTS = 96

        /** 段时长数组（复用，避免每次 [fire] 分配）。 */
        private val SEGMENT_TIMINGS = LongArray(SEGMENTS) { SEGMENT_MS }

        /** 把电平量化到 [BANDS] 档（0 .. BANDS-1），仅用于日志。 */
        internal fun bandOf(level: Float): Int =
            (level.coerceIn(0f, 1f) * BANDS).toInt().coerceIn(0, BANDS - 1)

        /**
         * 工厂：按设备权限/能力三选一。
         *
         * ⚠️ 权限判定是**必须**的，不是保险：模块运行在游戏进程，权限来自游戏
         * APK 的 manifest。官版游戏没有 VIBRATE，此时 `vibrate()` 会抛
         * `SecurityException`（`vibrateWithPermissionCheck` 内部
         * `enforceCallingOrSelfPermission`）。
         */
        fun create(
            context: Context,
            /** 抓地力反馈的最大振动强度（%）。 */
            slipPct: Int,
        ): HapticMixer {
            val hasPermission = context.checkSelfPermission(Manifest.permission.VIBRATE) ==
                PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                Logger.i(TAG, "HapticMixer: 无 VIBRATE 权限 → 走免权限 HapticFeedbackConstants 路")
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
                Logger.i(TAG, "HapticMixer: 无可用 Vibrator → 走免权限 HapticFeedbackConstants 路")
                return Fallback()
            }
            val hasAmplitude = runCatching { v.hasAmplitudeControl() }.getOrDefault(false)
            Logger.i(TAG, "HapticMixer: 真振幅路 hasAmplitudeControl=$hasAmplitude")
            return if (hasAmplitude) Envelope(v, slipPct) else Duty(v, slipPct)
        }

        /**
         * **路肩脉冲波形**——一次"撞击"= 一段**够长**的满幅通电 + 一段静默。
         *
         * ## 为什么是"长脉冲"而不是"短脉冲"（几轮踩坑的结论）
         *
         * LRA 的**起振时间约 50 ms**（器件规格：Rise Time Max 50 msec）——从
         * 静止推到满振幅需要这么久。`createWaveform` 的 amplitude 是**稳态
         * 目标幅度**，任何短于起振时间的脉冲只能拿到幅度的零头。所以：
         *
         * - 15 ms 脉冲 ⇒ 马达刚加速一点就开始衰减 ⇒ **软绵绵**（用户报
         *   「松松散散」「跟转子马达一样」）。
         * - **≥50 ms 满幅** ⇒ 马达有时间推到位 ⇒ 才有"一下是一下"的实体感。
         *
         * ## 为什么不用 primitive（本机与多数非 Pixel 设备都不行）
         *
         * 实机 `dumpsys` 证据（MEIZU 20 / Flyme）：
         * ```
         * supportedPrimitives = [CLICK(4ms), THUD(4ms), QUICK_FALL(4ms), ...]
         * ```
         * AOSP 推荐 THUD 是 **300 ms**，本机 HAL 全部压成 **4 ms** ⇒ primitive
         * 在本机等于空壳。而 `Composition` 叠多个 primitive 会退化成通用波形
         * （更短），实测**完全没振感**。
         *
         * ⇒ **跨设备唯一可靠的载具是 `createWaveform` + amplitude control**
         * （几乎所有现代机都有 `hasAmplitudeControl()`）。本函数完全自建波形，
         * 不依赖 HAL 对 primitive/prebaked 的实现程度。
         *
         * ## 波形结构
         *
         * ```
         * [满幅 pulseMs] [静默 silenceMs] [满幅 pulseMs] [静默 silenceMs] ... × N，循环
         * ```
         *
         * - `pulseMs = clamp(周期 × 0.6, 10, 200)`——**优先保证 ≥ 起振时间**，
         *   周期够长时封顶 200 ms（再长就变成"嗡"而不是"哒"）。
         * - 静默段填 **0**（不是抓地力载波）：路肩在时**路肩优先**，抓地力完全
         *   不参与——否则连续的抓地力载波会把路肩脉冲淹没（见 [Envelope.fire]）。
         *
         * @param kerb 路肩电平
         */
        /**
         * **兜底**：自建脉冲波形（不依赖 HAL 对 primitive 的实现）。
         *
         * ⚠️ 只在 [clickPrimitiveSupported] 为 false 时走这里。它是"有反馈但偏软"
         * 的路——LRA 起振要 50ms，而波形脉冲的 amplitude 是稳态目标值，短脉冲
         * 只能拿到零头。宁可软也不要没反馈（跨品牌兜底）。
         */
        private fun kerbPulseWave(
            kerb: Float, rateHz: Float,
        ): VibrationEffect {
            val periodMs = (1000f / rateHz).toLong().coerceAtLeast(10L)
            val pulseMs = (periodMs * 6L / 10L).coerceIn(10L, 200L)
            val silenceMs = (periodMs - pulseMs).coerceAtLeast(5L)
            // 波形总长约 1.2s（循环播放）——够长到不会在可感范围内回绕，
            // 又短到波形数组不臃肿。
            val reps = (1200L / (pulseMs + silenceMs)).toInt().coerceIn(1, 64)
            val timings = LongArray(reps * 2)
            val amps = IntArray(reps * 2)
            val on = carrier(kerb, KERB_FULL_PCT)
            for (i in 0 until reps) {
                timings[i * 2] = pulseMs
                amps[i * 2] = on
                timings[i * 2 + 1] = silenceMs
                amps[i * 2 + 1] = 0   // 静默段填 0（路肩优先，见 Envelope.fire）
            }
            // repeat = 0 = 从头无限循环（"不循环"是 -1）。
            return VibrationEffect.createWaveform(timings, amps, 0)
        }

        /** 把通道电平 × 该通道的最大强度映射成载波幅度（0..255）。0 就是真静音。 */
        private fun carrier(level: Float, maxIntensityPct: Int): Int =
            ((maxIntensityPct.coerceIn(0, 100) / 100f) * level.coerceIn(0f, 1f) * 255f)
                .toInt().coerceIn(0, 255)

        /** 平顶幅度数组（无路肩时用）：所有段同一个值。 */
        private fun buildFlat(carrier: Int): IntArray = IntArray(SEGMENTS) { carrier }

        /** 占空比数组（[Duty] 用）：`onCount` 个段通电，其余 0。 */
        private fun buildDuty(onCount: Int): IntArray = IntArray(SEGMENTS) { i ->
            // 均匀铺开：第 i 段是否通电，由"前 i+1 段累计应含多少个 on"决定
            //（Bresenham 式整数累积，避免浮点误差攒成周期性缺口）。
            if ((i + 1) * onCount / SEGMENTS > i * onCount / SEGMENTS) 255 else 0
        }

        /**
         * **脉冲长度**：3 段 × 5ms = **15 ms**。
         *
         * ## ⚠️ 15 ms 这个数字是"哒哒哒"与"嗡"的分水岭，不要再动
         *
         * 触觉感知科学的两条硬结论（Ng et al. 2021 *J Neurophysiol*；
         * Mountcastle et al. 1967；均见 `native/src/kerb_haptic.c` 常量区）：
         *
         * 1. **脉冲之间的静默时长决定感知频率**，且**静默 ≥15 ms 才被感知为
         *    独立事件**；更短则 LRA 来不及回落，两次脉冲在机械上连成一体。
         * 2. **<60 Hz = flutter（一个个"哒"），>60 Hz = 连续 hum**。
         *
         * 所以「哒哒哒」= 短脉冲（~15ms）+ 长静默。**绝不能做成 50% 占空**：
         * 那样静默只有半个周期，20~50Hz 时仅 10~25ms，LRA 平滑掉脉冲边界，
         * 听感就退化成低幅连续振动——正是用户报的「跟转子马达一样」。
         */
        private const val PULSE_SEGS = 3

        /**
         * 周期（段数）区间。`period` 段 × 5ms = 周期毫秒数。
         *
         * | period | 周期 | 频率 | 静默 |
         * |---|---|---|---|
         * | 20 段 | 100 ms | 10.0 Hz | 85 ms |
         * | 14 段 | 70 ms  | 14.3 Hz | 55 ms |
         * | 6 段  | 30 ms  | 33.3 Hz | 15 ms |
         *
         * ⚠️ **上限 6 段（33 Hz）是"较低频率上限"**：更快的脉冲串即使每拍都
         * 清楚，听感也会从"哒哒哒"滑向"嗡嗡"（60 Hz 以上完全是连续 hum）。
         * 下限放宽到 20 段（10 Hz）——低速压路肩本来就是慢悠悠的"哒……哒……"，
         * 拉宽窗口才能让游戏那套音高曲线（随速度变化）在体感上真正拉开差距；
         * 窗口太窄会让"快慢变化"根本听不出来。
         *
         * 静默 = period − [PULSE_SEGS] ∈ [3, 17] 段 = **15~85 ms**，下限恰好
         * 卡在离散感知的 15 ms 边界上（[PULSE_SEGS] 处有论证），全程每一拍
         * 都是干净的独立敲击。
         */
        private const val PERIOD_MIN_SEGS = 6
        private const val PERIOD_MAX_SEGS = 20

        /**
         * 把颗粒率（Hz）量化成**整数段周期**（段数）。
         *
         * ⚠️ 量化到段（5ms）是必须的——波形只能表达整数段；也正因为量化，
         * 颗粒率随车速连续变化时只有**跨过量化边界**才需要重发（见 [tick]），
         * 避免"每次车速微动都重发"把重发频率变成听得见的调制。
         */
        internal fun grainPeriodOf(rateHz: Float): Int {
            if (!(rateHz > 0f)) return PERIOD_MAX_SEGS
            var period = Math.round(1000f / rateHz / SEGMENT_MS)
            if (period < PERIOD_MIN_SEGS) period = PERIOD_MIN_SEGS
            if (period > PERIOD_MAX_SEGS) period = PERIOD_MAX_SEGS
            return period
        }

        /**
         * **路肩颗粒序列**：`IntArray` 长度 = 一个完整周期（`period` 段），
         * 开头 [PULSE_SEGS] 段为 1（通电）、其余为 0（静默）。
         *
         * ⚠️ 返回**一个周期**而非 96 段：调用方用它拼出刚好整数个周期的
         * 波形（见 [buildMixedAmps]）——周期的整数性是"循环回绕处无台阶"的
         * 前提，否则每圈会多/少一段，产生一个听得见的杂音。
         */
        private fun kerbPattern(period: Int): IntArray =
            IntArray(period) { i -> if (i < PULSE_SEGS) 1 else 0 }

        /**
         * 抓地力（平顶载波）与路肩（脉冲串）的**逐段取大**混合。
         *
         * ## 波形长度 = 整数个路肩周期
         *
         * 有路肩时**不**下发固定的 96 段，而是 `period × round(96 / period)`
         * 段（≈96，8~100 段之间）——保证波形里恰好含整数个周期。理由：
         * 波形以 `repeat = 0` **无限循环**，若长度不是周期的整数倍，每圈回绕
         * 处会多出（或吃掉）一段，相当于在规律的"哒哒哒"里插入一个杂拍。
         * 无路肩时退回 96 段平顶（与历史行为逐位一致）。
         */
        private fun buildMixedAmps(
            slip: Float, kerb: Float, rateHz: Float, grainOn: Boolean,
            slipPct: Int,
        ): IntArray {
            val slipCarrier = carrier(slip, slipPct)
            if (!grainOn) return buildFlat(slipCarrier)

            // ⚠️ **不做占空比补偿**：用户要的是"撞击的力度"，不是"平均能量"。
            // 每次脉冲都应当把马达推到滑条设定的幅度（这是"硬"的来源）。
            val kerbOn = carrier(kerb, KERB_FULL_PCT)
            val period = grainPeriodOf(rateHz)
            val pattern = kerbPattern(period)
            val total = period * ((SEGMENTS + period / 2) / period)
            return IntArray(total) { i ->
                val k = if (pattern[i % period] != 0) kerbOn else 0
                if (k > slipCarrier) k else slipCarrier
            }
        }
    }

    /**
     * 两条真振幅路的公共底座：持有 [Vibrator]、封装下发与停机。
     *
     * ⚠️ 只有**构造函数**是 `internal`：外部拿不到构造入口（唯一入口是
     * [Companion.create] 在确认 VIBRATE 权限之后调用）。**这同时是
     * `@SuppressLint("MissingPermission")` 正确性的边界**——豁免之所以成立，
     * 就是因为构造这条路只可能在权限已校验之后走通。子类保持 public 是为了让
     * [HapticMixer] 基类型之外仍有具名类型可用，而基类若为 internal 会触发
     * 「public 子类暴露 internal 超类型」编译错误。
     */
    abstract class AmplitudeMixer internal constructor(
        protected val vibrator: Vibrator,
    ) : HapticMixer() {

        /**
         * 本机 HAL 是否支持 `PRIMITIVE_CLICK`（**只作能力探测，不保证手感**）。
         *
         * ⚠️ 实测教训（MEIZU 20 / Flyme）：`arePrimitivesSupported` 返回 true 也
         * 不代表 HAL 真实现了——本机 `supportedPrimitives` 把**所有** primitive
         * 都列成 **4 ms**（AOSP 推荐 CLICK <20ms / THUD 300ms），prebaked 效果
         * 更是 130/130 全部 `with fallback`（HAL 没实现，框架兜底）。
         *
         * 但即便如此，**primitive 仍是本机唯一有"哒哒哒"实体感的路**——
         * HAL 对单个 primitive 会走**专用路径（带 overdrive）**，而
         * `createWaveform` 的脉冲受 LRA 50ms 起振时间限制，做出来是"松散"的。
         *
         * ⇒ 策略：**能用 primitive 就用（手感优先），不能就用 waveform 兜底
         * （保证所有机型都有反馈）**。见 [kerbPulse]。
         */
        protected val clickPrimitiveSupported: Boolean = Build.VERSION.SDK_INT >= 30 &&
            runCatching {
                vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK
                )
            }.getOrDefault(false)

        protected fun kerbPulse(
            kerb: Float, rateHz: Float, scale: Float,
        ): VibrationEffect {
            // ── 路径 A：HAL 支持 PRIMITIVE_CLICK ⇒ 用它（"哒哒哒"的唯一来源）──
            // 见 clickPrimitiveSupported 的说明：本机实测它是有实体感的路。
            if (clickPrimitiveSupported) {
                return VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                    .compose()
            }
            // ── 路径 B：兜底 waveform（无 primitive 的机型，保证有反馈）──
            return kerbPulseWave(kerb, rateHz)
        }

        override fun stopOutput() = cancel()

        /** 上一次路肩脉冲下发的时刻（0 = 未激活）。 */
        private var lastKerbFireMs = 0L

        /**
         * ⚠️ **路肩脉冲的统一节流**——**每个覆写 [fire] 的子类都必须先调它**。
         *
         * 路肩走**事件流**（见 [HapticMixer.tick]）——每拍一次独立起振。而
         * 轮询是 60Hz，不节流就等于每 16ms 重发一次，与颗粒率毫无关系
         * （实机症状：停在路肩上仍持续高频振动）。
         *
         * ⚠️ 它必须被**显式调用**而不是藏在基类 [fire] 里：`Envelope` 覆写了
         * `fire`（走 primitive），覆写会**整体替换**基类实现 —— 曾经把节流放在
         * 基类 `fire` 里，结果 Envelope 一路绕过去，60Hz 重发。
         *
         * @return true = 可以下发；false = 未到下一拍，调用方应立即返回
         */
        protected fun kerbThrottleAllows(rateHz: Float, grainOn: Boolean): Boolean {
            if (!grainOn) {
                lastKerbFireMs = 0L
                return true
            }
            val periodMs = (1000f / rateHz).toLong().coerceAtLeast(SEGMENT_MS)
            val now = SystemClock.uptimeMillis()
            if (lastKerbFireMs != 0L && now - lastKerbFireMs < periodMs) return false
            lastKerbFireMs = now
            return true
        }

        override fun fire(slip: Float, kerb: Float, rateHz: Float, grainOn: Boolean) {
            if (!kerbThrottleAllows(rateHz, grainOn)) return
            val amps = buildAmps(slip, kerb, rateHz, grainOn)
            // timings 必须与 amps 等长（否则 createWaveform 抛 IllegalArgumentException）。
            val timings = if (amps.size == SEGMENTS) SEGMENT_TIMINGS else LongArray(amps.size) { SEGMENT_MS }
            vibrate(VibrationEffect.createWaveform(timings, amps, REPEAT_FOREVER))
        }

        /** 生成幅度数组（子类实现具体编码）。长度可短于 [SEGMENTS]。 */
        protected abstract fun buildAmps(
            slip: Float, kerb: Float, rateHz: Float, grainOn: Boolean
        ): IntArray

        @SuppressLint("MissingPermission")   // 见上：构造前已由 create() 校验权限
        protected fun vibrate(effect: VibrationEffect) {
            runCatching { vibrator.vibrate(effect) }.onFailure {
                Logger.w(TAG, "HapticMixer: vibrate 失败 path=$pathTag", it)
            }
        }

        @SuppressLint("MissingPermission")
        protected fun cancel() {
            runCatching { vibrator.cancel() }.onFailure {
                Logger.w(TAG, "HapticMixer: cancel 失败", it)
            }
        }

        private companion object {
            /**
             * `createWaveform(timings, amplitudes, repeat)` 的 `repeat = 0` 是
             * **从头无限循环**（"不循环"是 `repeat = -1`）。
             */
            const val REPEAT_FOREVER = 0
        }
    }

    /**
     * 路径 A：**逐段取大的混合波形**（有振幅控制）。见类注释「一」。
     *
     * 抓地力通道的强度由 `slipPct` 决定；路肩通道**固定满幅**（[KERB_FULL_PCT]，
     * 2026-10-05 删除其独立强度滑条——primitive 路径下 HAL 忽略 `scale`，滑条空转）。
     */
    class Envelope internal constructor(
        vibrator: Vibrator,
        /** 抓地力反馈的最大振动强度（%）。 */
        private val slipPct: Int,
    ) : AmplitudeMixer(vibrator) {

        override val pathTag = "env"

        /**
         * 兜底实现（[fire] 已覆写，仅在 API < 30 的无路肩路径上经
         * `buildMixedAmps` 走到）。
         */
        override fun buildAmps(
            slip: Float, kerb: Float, rateHz: Float, grainOn: Boolean
        ): IntArray = buildMixedAmps(slip, kerb, rateHz, grainOn, slipPct)

        /**
         * ## ⚠️ 路肩必须走 HAL 的 primitive，不能自己拼 waveform
         *
         * LRA 的**起振时间约 50ms**（Precision Microdrives / INEED 规格表：
         * "Rise Time Max 50 msec"）——从静止到满振幅需要这么久。而
         * `createWaveform` 的 amplitude 是**稳态目标幅度**，任何短于 rise time
         * 的脉冲都只能拿到幅度的零头：15ms 通电 ⇒ LRA 刚加速一点就开始衰减
         * ⇒ 每个"哒"都是软绵绵的一小下 = 用户报的「松松散散」「跟转子马达
         * 一样」。
         *
         * **系统的 primitive 之所以"强烈"，是因为 HAL 用了 overdrive**（瞬时
         * 高压强行起振），4ms 就能推到位。这正是"长按哒一下"那种手感的来源，
         * 也是本模块自己拼波形永远做不到的事。
         *
         * 所以路肩段内改为**按颗粒周期重发一个 primitive**——这是 Android
         * 官方文档推荐的"连续 tick"做法（`startVibration()` +
         * `handler.postDelayed(this::startVibration, vibrationInterval)`），
         * 节奏由 [lastTapMs] 节流（轮询本身是 60Hz，够用）。
         *
         * ## ⚠️ 路肩在时**完全接管**（用户规格 2026-10-04）
         *
         * 之前把抓地力载波填进脉冲之间的静默段、并让 `scale` 取两条通道的
         * 较大值——结果是**抓地力把路肩淹没**（用户实测：「现在抓地力振感会
         * 淹没路肩振感」）。原因很直白：抓地力的平顶载波是**连续**的，能量
         * 总量远大于路肩那几十毫秒的脉冲；而脉冲的 scale 一旦被抓地力顶满，
         * 两次撞击之间还垫着抓地力的持续振动，听感就只剩"一片振"。
         *
         * ⇒ **路肩优先**：只要路肩在，就只发路肩脉冲——抓地力**完全不参与**
         * （既不出现在静默段，也不影响 scale）。抓地力在路肩结束后的下一帧
         * 自动恢复（它是电平驱动的状态量，见 [tick] 的状态同步分支）。
         */
        /**
         * 抓地力波形**当前是否在响**（它是 `repeat = 0` 的**无限循环**波形）。
         *
         * ⚠️ 这是"路肩上抓地力没被屏蔽"的**真正根因**：
         *
         * 抓地力下发的是 `createWaveform(..., repeat = 0)` —— **无限循环**。
         * 它的停止**只能**靠"下一个 `vibrate()` 把它顶掉"或显式 `cancel()`。
         * 而路肩走的是**单个 primitive**（本机 HAL 里只有 4ms）——4ms 播完就
         * 自然结束，**它顶掉旧波形的时机依赖厂商 HAL 的实现**；实测在
         * MEIZU/Flyme 上**没有可靠顶掉**，于是那条无限循环的抓地力波形继续
         * 在后台响 ⇒ 用户听到「路肩上抓地力还在响」「路肩被淹没」。
         *
         * ⇒ **切到路肩时必须显式 `cancel()`**（不能依赖 primitive 去顶）。
         */
        private var gripWavePlaying = false

        /** 上一次下发形态（日志用）：0=无，1=抓地力平顶，2=纯路肩。 */
        private var lastShape = 0

        /** 上一次**路肩颗粒（撞击）实际下发**的时刻——用于量"最后一颗→抓地力"的间隔。 */
        private var lastKerbTapMs = 0L

        /**
         * **桥接颗粒**（2026-10-05，修"最后一颗颗粒→抓地力可感"）。
         *
         * ⚠️ 实测（`sinceLastTap` 埋点，n=28）：离开路肩时「最后一颗颗粒 → 抓地力」
         * 间隔 **中位 36ms、最大 69ms**——路肩是**周期性**颗粒串（15 m/s → 53ms
         * 周期），最后一颗的落点随机，最多早一整个周期。更糟的是颗粒停后马达开始
         * 衰减（LRA 回落 ~50ms），抓地力从**静止**起振又要 ~50ms 斜坡 ⇒ 实际可感
         * 间隔 ≈ 36 + 50 ≈ 86ms。
         *
         * ⇒ **离开路肩那一帧补发一颗路肩颗粒**（用记下的路肩参数），下一帧再出
         * 抓地力。两个收益：
         * 1. 静默尾巴消失 ⇒ 间隔压到 **一轮询帧（~16ms）**；
         * 2. 马达仍在振铃 ⇒ 抓地力从"振铃态"接上，**没有起振斜坡**。
         *
         * ⚠️ 桥接颗粒是**路肩电平**（不是抓地力）⇒ 不违反"路肩优先 / 路肩期间
         * 抓地力完全静音"——它只是把"最后一颗颗粒"挪到 kerb-off 那一刻。
         */
        private var lastKerbLevel = 0f

        /** 桥接波形开头补几段满幅颗粒（= 一段 [SEGMENT_MS]，15ms）。 */
        private val KERB_BRIDGE_SEGS = 3

        override fun onLevelZero() {
            kerbBridgePending = false
        }

        /**
         * "本段路肩结束后还需补一颗桥接颗粒"。
         *
         * ⚠️ **必须是独立布尔**——若靠 `lastShape == 2` 判断，补完颗粒后 `lastShape`
         * 仍是 2 ⇒ 下一帧又补一颗 ⇒ **永远出不来抓地力**（无限补颗粒）。见 [lastKerbLevel]。
         */
        private var kerbBridgePending = false

        /**
         * ## ⚠️ 路肩在时**只有路肩**——抓地力一个字节都不参与（2026-10-05 最终定案）
         *
         * 我一度为了"无缝交接"在脉冲之间**垫了抓地力载波**（`[撞击][抓地力]`），
         * 结果用户立刻报「被抓地力振感淹没，没有屏蔽掉，松松散散」——**完全正确**：
         * 垫底就是"路肩期间抓地力在响"，正是用户从一开始就禁止的东西；而且载波
         * 填掉了脉冲之间的静默，LRA 不停机 ⇒ 撞击不再是一个个独立的"哒"，退化成
         * 连绵的低幅振动 = "松松散散"。
         *
         * ⇒ **路肩期间抓地力必须彻底静音**：脉冲之间的静默段就是**真静默**（不填
         * 任何东西）。"无缝交接"的诉求让位于"路肩优先"——这是用户的硬规格。
         *
         * ## 交接（离开路肩 → 抓地力）靠**响应速度**解决，不靠垫底
         *
         * 离场延迟的两个来源都已单独消除（见 [tick] 的 `grainChanged` 立即下发
         * 分支、以及 native 侧 `KERB_OFF_HOLD_FRAMES`）：电平一归零，**同一轮询帧**
         * 就下发抓地力平顶波形。剩下的只是 HAL 拆/建 conductor + LRA 起振的固有
         * 斜坡——**这是物理下限，任何"垫底"方案都是在用"路肩期间有抓地力"换它，
         * 不可接受**。
         */
        override fun fire(slip: Float, kerb: Float, rateHz: Float, grainOn: Boolean) {
            // ⚠️ **切到路肩必须显式 `cancel()`**：抓地力是 `repeat = 0` 的**无限
            // 循环**波形，路肩走 primitive（播完即止），**顶不掉它**（见 [gripWavePlaying]
            // 的论证）。不 cancel 的话路肩期间抓地力会在后台继续响 = 用户报的
            // 「没有屏蔽掉」。
            // ⚠️ 必须放在节流之前：否则路肩第一帧被节流挡掉时直接 return，那条
            // 循环中的抓地力波形就活下来了。
            if (grainOn && gripWavePlaying) {
                cancel()
                gripWavePlaying = false
            }
            // ⚠️ 本函数覆写了基类 fire，节流必须**显式**调（见 kerbThrottleAllows）。
            if (!kerbThrottleAllows(rateHz, grainOn)) return

            if (!grainOn) {
                // ── 离开路肩那一帧：**桥接波形**（见 [kerbBridgePending] 论证）──
                // 一条波形里 `[最后一颗颗粒][抓地力载波…]` 直接接上 ⇒ 既补上"最后一颗
                // 颗粒"（消掉静默尾巴），又让抓地力从**同一段连续输出**里长出来
                // （没有停机、没有起振斜坡）。**一次 vibrate、零空档**。
                if (kerbBridgePending && lastKerbLevel > 0f) {
                    kerbBridgePending = false
                    val bridgeAmp = carrier(lastKerbLevel, KERB_FULL_PCT)
                    val slipCarrier = carrier(slip, slipPct)
                    val amps = buildFlat(slipCarrier).copyOf()
                    val n = KERB_BRIDGE_SEGS.coerceIn(1, SEGMENTS - 1)
                    for (i in 0 until n) amps[i] = bridgeAmp
                    // ⚠️ `repeat = n`（不是 0）：`createWaveform` 的 repeat 参数是
                    // **回绕起点段索引** —— 前 n 段（颗粒）只播一次，之后从第 n 段
                    // （抓地力载波）**无限循环**。这样"补一颗颗粒"不会每 480ms 重放。
                    vibrate(VibrationEffect.createWaveform(SEGMENT_TIMINGS, amps, n))
                    gripWavePlaying = true
                    lastKerbTapMs = SystemClock.uptimeMillis()
                    logShape(1, slipCarrier)
                    return
                }
                // 无路肩：抓地力连续平顶。
                val slipCarrier = carrier(slip, slipPct)
                vibrate(VibrationEffect.createWaveform(SEGMENT_TIMINGS, buildFlat(slipCarrier), 0))
                gripWavePlaying = true
                logShape(1, slipCarrier)
                return
            }
            // 有路肩：**纯路肩脉冲**，静默段是真静默（抓地力完全不参与）。
            // 强度**固定满幅**（无独立滑条，见 Envelope 类注释）。
            lastKerbLevel = kerb
            kerbBridgePending = true
            vibrate(kerbPulse(kerb, rateHz, KERB_FULL_SCALE))
            lastKerbTapMs = SystemClock.uptimeMillis()
            logShape(2, 0)
        }

        /** 形态切换日志——只在形态变化时打一条（诊断路肩/抓地力是否互串）。 */
        private fun logShape(shape: Int, slipCarrier: Int) {
            if (shape == lastShape) return
            // 路肩→抓地力：记录"最后一颗颗粒到现在"的间隔（用户报的"可感间隔"）。
            val tail = if (shape == 1 && lastShape == 2 && lastKerbTapMs != 0L)
                " sinceLastTap=${SystemClock.uptimeMillis() - lastKerbTapMs}ms" else ""
            val names = arrayOf("none", "grip-flat", "kerb-only")
            Logger.i(TAG, "HapticMixer: shape %s→%s slip=%d%s path=$pathTag".format(
                names[lastShape], names[shape], slipCarrier, tail))
            lastShape = shape
        }
    }

    /**
     * 路径 B：**无振幅控制**（有 VIBRATE 但硬件不报 `hasAmplitudeControl`）。
     *
     * 这类设备把任何非零振幅一律按满幅播放，强度只能靠"每个周期通多久"表达。
     *
     * - **无路肩**：抓地力走占空比（下限 50%
     *   以免又回到用户否定的"哒哒哒"）。
     * - **有路肩**：颗粒图案本身就是占空比，直接用它——此时**抓地力的强度维度
     *   让位**（硬件本来就没有振幅维度，无法同时表达两个强度）。
     */
    class Duty internal constructor(
        vibrator: Vibrator,
        private val slipPct: Int,
    ) : AmplitudeMixer(vibrator) {

        override val pathTag = "duty"

        override fun buildAmps(
            slip: Float, kerb: Float, rateHz: Float, grainOn: Boolean
        ): IntArray {
            if (grainOn) {
                // 颗粒掩码直接当占空比：on 段满幅、off 段静音。
                // 长度取整数个周期（循环回绕处无台阶，同 buildMixedAmps）。
                val period = grainPeriodOf(rateHz)
                val pattern = kerbPattern(period)
                val total = period * ((SEGMENTS + period / 2) / period)
                return IntArray(total) { i -> if (pattern[i % period] != 0) 255 else 0 }
            }
            val strength = (slipPct.coerceIn(0, 100) / 100f) * slip.coerceIn(0f, 1f)
            val duty = 0.5f + 0.5f * strength          // ∈ [0.5, 1]
            val onCount = (duty * SEGMENTS).toInt().coerceIn(SEGMENTS / 2, SEGMENTS)
            return buildDuty(onCount)
        }
    }

    /**
     * 路径 C：**免权限**（[View.performHapticFeedback]）。
     *
     * 官方文档明确「using HapticFeedbackConstants with a View doesn't require
     * the VIBRATE permission」。代价是**没有任何幅度/波形控制**——这条路做不到
     * 共存版那种 `createWaveform(96×5ms, repeat = 0)` 的平顶无限循环波形
     * （能传 `VibrationEffect` 的 `vibrate()` 事务在服务端
     * `VibratorManagerService.vibrateWithPermissionCheck` 里有
     * `enforceCallingOrSelfPermission("android.permission.VIBRATE", "vibrate")`，
     * 反汇编实证），**只能用高频叩击逼近"LRA 持续通电"**。
     *
     * ## 两个通道的节奏是**相反**的，必须分开计时
     *
     * | 通道 | 目标手感 | 叩击间隔 | 理由 |
     * |---|---|---|---|
     * | 抓地力 | **连续绵密** | [FALLBACK_GRIP_INTERVAL_MS]（10ms，≈62Hz 以上） | 推过 60Hz 的 flutter/hum 分界，LRA 来不及回落 |
     * | 路肩 | **离散撞击** | `max(1000/rateHz, [FALLBACK_KERB_MIN_INTERVAL_MS])`（≥22ms） | 颗粒率随车速；下限卡在 flutter 区 |
     *
     * ⚠️ **绝不能共用一个常量 / 一个计时器**——2026-10-05 的 bug 就是这么来的：
     * 两者共用 22ms（≈31Hz，落在 flutter 区），抓地力被动继承了路肩的节奏 ⇒
     * 官版抓地力听感 = 「类似路肩的哒哒哒」（用户实机反馈）。抓地力与路肩在
     * `tick()` 层已经是互斥的（路肩优先），这里也必须各自独立。
     */
    class Fallback : HapticMixer() {

        private companion object {
            /**
             * 强度档 → 候选常量（每档两个，按 ROM 差异取 SDK 支持的第一个）。
             *
             * ## ⚠️ 三档只恢复"强弱"，**不解决"哒"**
             *
             * 2026-10-06 实测两条已证伪的路线（都在官版实机跑过）：
             * ① 把叩击间隔压到 13~17ms（独立 ticker）⇒ 用户："**更快的**哒哒哒"；
             * ② 去掉强度档、全程只用 `CLOCK_TICK`（唯一"供重复使用"的常量）⇒
             *    用户："还是快速的哒哒哒，且**判断不了强弱**"。
             *
             * ⇒ 免权限路（[View.performHapticFeedback]）**做不出绵密**：它只接受
             * 语义常量、效果由系统预置表生成，每次 `requestEnd` 都打断上一次 ⇒
             * 无论多密都是"敲一下、掐掉、再敲一下"。这是 API 边界，不是参数问题。
             *
             * ⇒ 既然绵密做不到，就**别把强度也赔进去**：恢复三档，保住"能判断
             * 强弱"这个功能维度。
             */
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

        /**
         * 抓地力通道的**独立高频 ticker**。
         *
         * ## 为什么必须脱离 60Hz 轮询
         *
         * 本路径唯一的连续感来源是"叩击间隔 ≤ LRA 振铃时长"（见 [FALLBACK_GRIP_INTERVAL_MS]
         * 的论证）。而 [fire] 只被 [SlipFeedbackView] 的 16ms 轮询调用 ⇒ **叩击率被
         * 硬钳在 62.5Hz**：把常量写成 10ms 也没用，因为两次 `fire` 之间本来就隔 16ms。
         * 所以要用一个**比轮询更快**的 ticker 独立驱动抓地力叩击。
         *
         * ⚠️ 只在抓地力激活时跑，电平归零即 `removeCallbacks`（见 [stopGripTicker]）——
         * 常驻轮询会让马达在没信号时也被反复唤醒。
         */
        private val gripTicker = object : Runnable {
            override fun run() {
                val v = view
                val level = gripLevel
                if (v == null || level < MIN_TRIGGER_LEVEL) {
                    // view 已摘除或电平归零：自己退出并复位标志，避免"标志为真但
                    // 回调已死"导致后续再也启动不起来。
                    gripTickerRunning = false
                    return
                }
                fireOne(v, level)
                handler.postDelayed(this, FALLBACK_GRIP_INTERVAL_MS)
            }
        }

        private val handler = Handler(Looper.getMainLooper())

        /** 最近一次 [fire] 收到的抓地力电平（ticker 据此叩击）。 */
        private var gripLevel = 0f

        private var gripTickerRunning = false

        /** **路肩通道**上一次叩击时刻（路肩走事件流，与 ticker 分开计时）。 */
        private var lastKerbTapMs = 0L

        // 没有持续输出能力：只能按节奏反复叩击（见基类 continuous 的说明）。
        override val continuous: Boolean get() = false

        override val pathTag = "hfc"

        override fun attachView(view: View?) {
            this.view = view
        }

        /**
         * ⚠️ **电平归零必须停 ticker 并清电平**。
         *
         * [gripLevel] 只在 [fire] 里更新，而 `fire` 在电平跌破 [MIN_TRIGGER_LEVEL]
         * 时**根本不会被调用**（基类 `tick` 走归零分支直接 return）⇒ 电平会永久
         * 冻结在最后一次驾驶的值上，ticker 也就永远停不下来（马达无限叩击）。
         * 基类归零分支会调 [onLevelZero]，这是唯一可靠的清零点。
         */
        override fun onLevelZero() {
            stopGripTicker()
            gripLevel = 0f
            lastKerbTapMs = 0L
        }

        override fun fire(slip: Float, kerb: Float, rateHz: Float, grainOn: Boolean) {
            val v = view ?: return
            val now = SystemClock.uptimeMillis()

            if (grainOn && rateHz > 1f) {
                // ── 路肩：**事件流**，叩击间隔 = 颗粒周期（速率随车速），下限 22ms ──
                // 路肩要的就是**离散撞击**（哒哒哒是它的设计目标），所以下限卡在
                // flutter 区（≈45Hz），且**不受 ticker 影响**（ticker 只管抓地力）。
                stopGripTicker()
                val interval = (1000f / rateHz).toLong()
                    .coerceAtLeast(FALLBACK_KERB_MIN_INTERVAL_MS)
                if (lastKerbTapMs != 0L && now - lastKerbTapMs < interval) return
                lastKerbTapMs = now
                // ⚠️ 路肩期间**强度只取路肩电平**，不与抓地力取大。
                // `tick()` 里"路肩优先"已经把抓地力排除在 peak 之外，若这里再用
                // `max(slip, kerb)` 就把它从后门放回来了（共存版 Envelope.fire 是
                // 严格只用 kerb 的，两路必须一致）。
                fireOne(v, kerb)
                return
            }

            // ── 抓地力：交给独立 ticker（间隔 [FALLBACK_GRIP_INTERVAL_MS]，远快于轮询）──
            lastKerbTapMs = 0L
            gripLevel = slip
            startGripTicker()
        }

        /** 启动抓地力 ticker（已在跑则只更新电平，不重排）。 */
        private fun startGripTicker() {
            if (gripTickerRunning) return
            gripTickerRunning = true
            handler.post(gripTicker)
        }

        /** 停抓地力 ticker（切到路肩 / 电平归零 / view 摘除时调）。 */
        private fun stopGripTicker() {
            if (!gripTickerRunning) return
            gripTickerRunning = false
            handler.removeCallbacks(gripTicker)
        }

        /**
         * 按电平选强度档并叩一下。
         *
         * ⚠️ 这条路**没有幅度维度**（`performHapticFeedback` 只接受语义常量，
         * 实际效果由系统预置表决定），所以"强度"只能退化为**选哪个常量**。
         * 三档映射是唯一可用的表达——**别去掉它**：去掉后用户"判断不了强弱"
         * （2026-10-06 实测），而"哒"的问题**本来就不在这里**（见 [LEVEL_CANDIDATES]）。
         */
        private fun fireOne(v: View, level: Float) {
            val idx = (level * 3f).toInt().coerceIn(0, 2)
            val constant = LEVEL_CANDIDATES[idx].firstOrNull { it != 0 }
                ?: HapticFeedbackConstants.VIRTUAL_KEY
            runCatching {
                v.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            }
        }

        override fun stopOutput() {
            // 免权限路是离散叩击，没有"持续输出"要停：停 ticker + 清计时。
            stopGripTicker()
            gripLevel = 0f
            lastKerbTapMs = 0L
        }
    }
}
