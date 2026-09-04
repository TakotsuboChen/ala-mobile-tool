package tools.alamobile.mod.offsets

/**
 * IL2CPP method/field offsets for Ala Mobile 8.0.6 (versionCode 200150).
 *
 * These values are generated from the Il2CppDumper output. Do NOT hard-code
 * guessed offsets; run `tools/run-il2cpp-dumper.sh` after every game update
 * and regenerate this file.
 *
 * Method offsets are the *RVA* values from Il2CppDumper (relative virtual
 * addresses in libil2cpp.so). `dl_iterate_phdr` returns the ELF base address,
 * which corresponds to RVA 0, so we must use RVA here, not the file offset.
 *
 * Migration from 8.0.4 (200146): game logic methods +0x2718~+0x27E0,
 * BillingManager +0xF24, lap timing +0x1E48/+0x23E8, Unity engine region remapped.
 * Instance field offsets unchanged.
 */
object OffsetTable {

    // ── 版本标识（非 RVA）──
    // 当前适配的游戏 versionCode。VersionGate 是版本门禁的单一事实源，
    // 此处是围场上传/版本榜过滤键的引用副本（PaddockUploader version_code）。
    const val PADDOCK_VERSION_CODE: Int = 200150

    // IRDSCarControllInput (TypeDefIndex: 332, was 328)
    // RVA shift: +0xDC1C from 8.0.0
    const val IRDS_CAR_CONTROLL_INPUT_SET_THROTTLE: Long = 0x1A65528L
    const val IRDS_CAR_CONTROLL_INPUT_SET_BRAKE: Long = 0x1A6550CL
    const val IRDS_CAR_CONTROLL_INPUT_SET_CLUTCH: Long = 0x1A65560L
    const val IRDS_CAR_CONTROLL_INPUT_SHIFT_UP: Long = 0x1A67324L
    const val IRDS_CAR_CONTROLL_INPUT_SHIFT_DOWN: Long = 0x1A67378L
    const val IRDS_CAR_CONTROLL_INPUT_DRS_TOGGLE: Long = 0x1A673E0L
    const val IRDS_CAR_CONTROLL_INPUT_FIXED_UPDATE: Long = 0x1A66C3CL

    // IRDSCarControllInput instance fields (relative to instance base)
    // Unchanged from 8.0.0 (class layout identical)
    const val IRDS_CAR_CONTROLL_INPUT_THROTTLE_FIELD: Long = 0x174L // _inputTorque
    const val IRDS_CAR_CONTROLL_INPUT_BRAKE_FIELD: Long = 0x178L    // _brake
    const val IRDS_CAR_CONTROLL_INPUT_ACTUAL_THROTTLE_FIELD: Long = 0x16CL // actualInputTorque
    const val IRDS_CAR_CONTROLL_INPUT_ACTUAL_BRAKE_FIELD: Long = 0x170L    // actualBrake
    const val IRDS_CAR_CONTROLL_INPUT_CLUTCH_FIELD: Long = 0xD0L    // inputClutch

    // IRDSDrivetrain (TypeDefIndex: 341, was 337)
    // RVA shift: +0xDC1C from 8.0.0
    const val IRDS_DRIVETRAIN_SET_GEAR: Long = 0x1A6D348L
    const val IRDS_DRIVETRAIN_SHIFT_UP: Long = 0x1A6F568L
    const val IRDS_DRIVETRAIN_SHIFT_DOWN: Long = 0x1A6F6ECL
    const val IRDS_DRIVETRAIN_FIXED_UPDATE: Long = 0x1A6E1D4L
    // DoGearShifting — 自动换挡的唯一入口（FixedUpdate 每帧调用）。
    // hook 它并在 orig 前设 overrideClutchManagement(0x15C)=1 + automatic(0xBC)=1，
    // 让 DoGearShifting 开头 direct return，真正禁用自动换挡。
    // 现有 proxy_drivetrain_fixed_update 写 automatic 会被 FixedUpdate 每帧覆盖，
    // 所以必须在 DoGearShifting 层拦截。
    const val IRDS_DRIVETRAIN_DO_GEAR_SHIFTING: Long = 0x1A6EB30L

    // TractionFilter / HandleABS — TC/ABS 入口方法。直接 hook 这两个方法，
    // 在入口处根据模块开关决定是否跳过，比写字段更可靠（不受游戏每帧覆盖影响）。
    const val IRDS_CAR_CONTROLL_INPUT_TRACTION_FILTER: Long = 0x1A673FCL
    const val IRDS_CAR_CONTROLL_INPUT_HANDLE_ABS: Long = 0x1A67970L

    // IRDSDrivetrain instance fields
    // Unchanged from 8.0.0
    const val IRDS_DRIVETRAIN_CURRENT_GEAR_FIELD: Long = 0xC0L
    const val IRDS_DRIVETRAIN_THROTTLE_FIELD: Long = 0xB4L
    const val IRDS_DRIVETRAIN_THROTTLE_INPUT_FIELD: Long = 0xB8L
    const val IRDS_DRIVETRAIN_AUTOMATIC_FIELD: Long = 0xBCL

    // IRDSPlayerControls (TypeDefIndex: 353, was 349)
    // Update() 每帧调用，hook 它来持续刷新 g_last_controller——
    // 从 this+0x60 (carInputs) 读当前玩家车的 IRDSCarControllInput。
    // IRDSPlayerControls 只挂在玩家车 GameObject 上，天然身份过滤。
    // 解决"重新开始"后旧 controller 实例失效、g_last_controller 停在野指针的问题。
    // RVA shift: +0xDC1C from 8.0.0
    const val IRDS_PLAYER_CONTROLS_UPDATE: Long = 0x1A73200L

    // BillingManager (TypeDefIndex: 915, was 910)
    // RVA shift: +0x6340 from 8.0.0
    const val BILLING_MANAGER_AWAKE: Long = 0x1873EF4L
    const val BILLING_MANAGER_INITIALIZE_BILLING: Long = 0x1874084L
    const val BILLING_MANAGER_ON_OWNED_NONE: Long = 0x1875590L
    const val BILLING_MANAGER_ON_PURCHASE_FAILED: Long = 0x1875504L
    const val BILLING_MANAGER_SET_UNLOCKED: Long = 0x18756A4L
    const val BILLING_MANAGER_GET_INSTANCE: Long = 0x1873BBCL
    // OnAlreadyOwned(string productId) — 主动注入"已拥有"的 IL2CPP 实例方法。
    // 不依赖 Java BillingBridge.checkOwned → sendUnityMessage 回调链，
    // 让 OnOwnedNone 被拦截后直接调用此方法完成解锁。
    const val BILLING_MANAGER_ON_ALREADY_OWNED: Long = 0x1875418L

    // BillingManager instance fields
    // Unchanged from 8.0.0
    const val BILLING_MANAGER_IS_UNLOCKED_FIELD: Long = 0x20L
    const val BILLING_MANAGER_HAS_STORE_CONNECTION_FIELD: Long = 0x21L
    const val BILLING_MANAGER_HAS_COMPLETED_OWNERSHIP_CHECK_FIELD: Long = 0x22L

    // Music / Main Menu (handleMusicVolume) (TypeDefIndex: 289, was 288)
    // RVA shift: +0xDDCC from 8.0.0
    const val HANDLE_MUSIC_VOLUME_UPDATE: Long = 0x1A555E4L
    const val HANDLE_MUSIC_VOLUME_START: Long = 0x1A5558CL
    // AudioSource.set_volume(float) — 用于静音游戏主菜单音乐
    // ⚠️ 8.0.4 中大幅变化（-0x1A32384），暗示 Unity 引擎版本升级。
    // 注意：这个 RVA 实际指向 TweenVolume.set_volume（NGUI 补间助手），
    // 主菜单音乐走 TweenVolume 驱动所以能用。
    const val AUDIO_SOURCE_SET_VOLUME: Long = 0x1810C4CL

    // IntroLogoManager (TypeDefIndex: 317) — 开场动画管理器
    // Start() 是开场动画入口，hook 它拿"开场开始"信号 + 静音 introSound。
    const val INTRO_LOGO_MANAGER_START: Long = 0x1A61344L
    // 真正的 AudioSource.set_volume(float) — Unity 引擎方法（非 TweenVolume）。
    // 开场 introSound 是直接播放的 AudioSource，必须用这个而非 TweenVolume 版本。
    const val AUDIO_SOURCE_SET_VOLUME_REAL: Long = 0x3252BECL

    // ── 计时赛有效圈速监听（lap_hook，log-only）──
    // IRDSLevelLoadVariables::Awake()（protected override）— LLV 单例创建入口。
    // hook 捕获实例后读 trackToRace (0xB8, string)=赛道名（16 条 GP 赛道）。
    const val IRDS_LEVEL_LOAD_VARIABLES_AWAKE: Long = 0x199FC70L
    // odometerHandler (TypeDefIndex: 1774)::HandleSectorsTimes(int, float, int,
    // bool, float) — 游戏自己的圈段事件（显式 validLap + totalLapTime 语义），
    // ~3 次/圈，模块只读透传不打扰。
    const val ODOMETER_HANDLER_HANDLE_SECTORS_TIMES: Long = 0x1A0C5ACL

    // ── 隐藏游戏原生踏板按钮（hide_pedals_hook）──
    // IRDSUIMobileControls::getInstance() — 拿 UI 管理器实例后遍历 layouts 找 MobileControls。
    const val IRDS_UI_MOBILE_CONTROLS_GET_INSTANCE: Long = 0x174D5C0L
    // Unity 引擎方法（UnityEngine 区，8.0.6 Unity 补丁升级后整区重排）。
    const val UNITY_GAME_OBJECT_SET_ACTIVE: Long = 0x329FEE4L
    const val UNITY_GAME_OBJECT_GET_ACTIVE_SELF: Long = 0x329FF28L
    const val UNITY_GAME_OBJECT_GET_TRANSFORM: Long = 0x329FDA8L
    const val UNITY_COMPONENT_GET_GAME_OBJECT: Long = 0x329CAA0L
    const val UNITY_TRANSFORM_GET_CHILD: Long = 0x32AE49CL
    const val UNITY_TRANSFORM_GET_CHILD_COUNT: Long = 0x32ADE34L
    const val UNITY_OBJECT_GET_NAME: Long = 0x32A408CL
}