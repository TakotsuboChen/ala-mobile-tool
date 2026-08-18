package tools.alamobile.mod.offsets

/**
 * IL2CPP method/field offsets for Ala Mobile 8.0.4 (versionCode 200146).
 *
 * These values are generated from the Il2CppDumper output. Do NOT hard-code
 * guessed offsets; run `tools/run-il2cpp-dumper.sh` after every game update
 * and regenerate this file.
 *
 * Method offsets are the *RVA* values from Il2CppDumper (relative virtual
 * addresses in libil2cpp.so). `dl_iterate_phdr` returns the ELF base address,
 * which corresponds to RVA 0, so we must use RVA here, not the file offset.
 *
 * Migration from 8.0.0 (200142): All game logic methods shifted by +0xDC1C,
 * BillingManager by +0x6340, handleMusicVolume by +0xDDCC.
 * Instance field offsets unchanged.
 */
object OffsetTable {

    // IRDSCarControllInput (TypeDefIndex: 332, was 328)
    // RVA shift: +0xDC1C from 8.0.0
    const val IRDS_CAR_CONTROLL_INPUT_SET_THROTTLE: Long = 0x1A62E2CL
    const val IRDS_CAR_CONTROLL_INPUT_SET_BRAKE: Long = 0x1A62E10L
    const val IRDS_CAR_CONTROLL_INPUT_SET_CLUTCH: Long = 0x1A62E48L
    const val IRDS_CAR_CONTROLL_INPUT_SHIFT_UP: Long = 0x1A64C0CL
    const val IRDS_CAR_CONTROLL_INPUT_SHIFT_DOWN: Long = 0x1A64C60L
    const val IRDS_CAR_CONTROLL_INPUT_DRS_TOGGLE: Long = 0x1A64CC8L
    const val IRDS_CAR_CONTROLL_INPUT_FIXED_UPDATE: Long = 0x1A64524L

    // IRDSCarControllInput instance fields (relative to instance base)
    // Unchanged from 8.0.0 (class layout identical)
    const val IRDS_CAR_CONTROLL_INPUT_THROTTLE_FIELD: Long = 0x174L // _inputTorque
    const val IRDS_CAR_CONTROLL_INPUT_BRAKE_FIELD: Long = 0x178L    // _brake
    const val IRDS_CAR_CONTROLL_INPUT_ACTUAL_THROTTLE_FIELD: Long = 0x16CL // actualInputTorque
    const val IRDS_CAR_CONTROLL_INPUT_ACTUAL_BRAKE_FIELD: Long = 0x170L    // actualBrake
    const val IRDS_CAR_CONTROLL_INPUT_CLUTCH_FIELD: Long = 0xD0L    // inputClutch

    // IRDSDrivetrain (TypeDefIndex: 341, was 337)
    // RVA shift: +0xDC1C from 8.0.0
    const val IRDS_DRIVETRAIN_SET_GEAR: Long = 0x1A6AC30L
    const val IRDS_DRIVETRAIN_SHIFT_UP: Long = 0x1A6CD28L
    const val IRDS_DRIVETRAIN_SHIFT_DOWN: Long = 0x1A6CF0CL
    const val IRDS_DRIVETRAIN_FIXED_UPDATE: Long = 0x1A6B9F4L
    // DoGearShifting — 自动换挡的唯一入口（FixedUpdate 每帧调用）。
    // hook 它并在 orig 前设 overrideClutchManagement(0x15C)=1 + automatic(0xBC)=1，
    // 让 DoGearShifting 开头 direct return，真正禁用自动换挡。
    // 现有 proxy_drivetrain_fixed_update 写 automatic 会被 FixedUpdate 每帧覆盖，
    // 所以必须在 DoGearShifting 层拦截。
    const val IRDS_DRIVETRAIN_DO_GEAR_SHIFTING: Long = 0x1A6C350L

    // TractionFilter / HandleABS — TC/ABS 入口方法。直接 hook 这两个方法，
    // 在入口处根据模块开关决定是否跳过，比写字段更可靠（不受游戏每帧覆盖影响）。
    const val IRDS_CAR_CONTROLL_INPUT_TRACTION_FILTER: Long = 0x1A64CE4L
    const val IRDS_CAR_CONTROLL_INPUT_HANDLE_ABS: Long = 0x1A65258L

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
    const val IRDS_PLAYER_CONTROLS_UPDATE: Long = 0x1A70A20L

    // BillingManager (TypeDefIndex: 915, was 910)
    // RVA shift: +0x6340 from 8.0.0
    const val BILLING_MANAGER_AWAKE: Long = 0x1872FD0L
    const val BILLING_MANAGER_INITIALIZE_BILLING: Long = 0x1873160L
    const val BILLING_MANAGER_ON_OWNED_NONE: Long = 0x187466CL
    const val BILLING_MANAGER_ON_PURCHASE_FAILED: Long = 0x18745E0L
    const val BILLING_MANAGER_SET_UNLOCKED: Long = 0x1874780L
    const val BILLING_MANAGER_GET_INSTANCE: Long = 0x1872C98L
    // OnAlreadyOwned(string productId) — 主动注入"已拥有"的 IL2CPP 实例方法。
    // 不依赖 Java BillingBridge.checkOwned → sendUnityMessage 回调链，
    // 让 OnOwnedNone 被拦截后直接调用此方法完成解锁。
    const val BILLING_MANAGER_ON_ALREADY_OWNED: Long = 0x18744F4L

    // BillingManager instance fields
    // Unchanged from 8.0.0
    const val BILLING_MANAGER_IS_UNLOCKED_FIELD: Long = 0x20L
    const val BILLING_MANAGER_HAS_STORE_CONNECTION_FIELD: Long = 0x21L
    const val BILLING_MANAGER_HAS_COMPLETED_OWNERSHIP_CHECK_FIELD: Long = 0x22L

    // Music / Main Menu (handleMusicVolume) (TypeDefIndex: 289, was 288)
    // RVA shift: +0xDDCC from 8.0.0
    const val HANDLE_MUSIC_VOLUME_UPDATE: Long = 0x1A52ECCL
    const val HANDLE_MUSIC_VOLUME_START: Long = 0x1A52E74L
    // AudioSource.set_volume(float) — 用于静音游戏主菜单音乐
    // ⚠️ 8.0.4 中大幅变化（-0x1A32384），暗示 Unity 引擎版本升级。
    // 注意：这个 RVA 实际指向 TweenVolume.set_volume（NGUI 补间助手），
    // 主菜单音乐走 TweenVolume 驱动所以能用。
    const val AUDIO_SOURCE_SET_VOLUME: Long = 0x18100E8L

    // IntroLogoManager (TypeDefIndex: 317) — 开场动画管理器
    // Start() 是开场动画入口，hook 它拿"开场开始"信号 + 静音 introSound。
    const val INTRO_LOGO_MANAGER_START: Long = 0x1A5EC2CL
    // 真正的 AudioSource.set_volume(float) — Unity 引擎方法（非 TweenVolume）。
    // 开场 introSound 是直接播放的 AudioSource，必须用这个而非 TweenVolume 版本。
    const val AUDIO_SOURCE_SET_VOLUME_REAL: Long = 0x325040CL
}