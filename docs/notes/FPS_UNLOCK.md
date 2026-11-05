# 强制全局 120 FPS（enableForce120Fps）

> 状态：**活跃** · 最后编纂：2026-11-05
> 一句话结论：游戏移动端 UI 只给 30/60，但引擎支持 120；模块 hook `Application.set_targetFrameRate` 改写入参，不碰任何游戏字段。

---

## 1. 结论（发现）

### 1.1 引擎支持 120，UI 不给出口

- `[V]` `odometerHandler.setGameAndGraphicsVariables()`（RVA `0x1A02C44`）按
  `GlobalVariables.sV->_graphicsFramerate`（settingsValues `+0x20`）分**三支精确相等**判定，
  各接一个 `bl Application.set_targetFrameRate`：

  | `_graphicsFramerate` | 指令 | 帧率 |
  |---|---|---|
  | `0` | `mov w0, #0x1e` | 30 |
  | `1` | `mov w0, #0x3c` | 60 |
  | `2` | `mov w0, #0x78` | **120** |
  | 其它 | 无 | 不调用 |

- `[V]` 移动端「画面 → 帧率」下拉只有 **30 / 60** 两项（用户设备实测确认）。
  三套画布同存于 `datapack.unity3d`：
  `SettingCanvasSwitch`（30/60）、`SettingCanvas`（30/60）、
  `SettingCanvasStandalone`（30/60/**120**，PC 版画布，Android 够不到）。
  ⇒ 玩家永远只能写 0 或 1，`2` 不可达。

- `[V]` 无存档时默认 `_graphicsFramerate = 0`（= 30 fps）。默认值由
  `initializeSettingValuesForOpenGL`(0x1882E48) / `...Vulkan`(0x1882EF4)
  从 `.rodata` 常量灌入，两条分支**帧率都是 0**。

### 1.2 `set_targetFrameRate` 的实际调用点

- `[V]` 全 `.so` 五个 `bl` 调用点：

  | 地址 | 所属方法 | 传值 | 是否执行 |
  |---|---|---|---|
  | `0x1a02ce4` / `0x1a02d34` / `0x1a02d84` | `odometerHandler.setGameAndGraphicsVariables` | 30/60/120 | ✅ |
  | `0x18d8ad0` | `EffectsGui.Start` | 300 | ❌ 死代码 |
  | `0x18d8fb8` | `MainGui.Start` | 300 | ❌ 死代码 |

- `[V]` `EffectsGui` / `MainGui` 是 Unity 官方「3D Graphics Settings」示例工程遗留：
  序列化字段（`MirrorFloor` / `RefractionGrabPass` / `NormalFloor` / `BlurGaus`）
  在 `datapack.unity3d`（225677 个 GameObject）与 `common_assets_all_*.bundle`
  里**全部零命中**，两个组件从未实例化。`.so` 里的 `'3D Graphics Settings'`
  字符串（`0x37AB078`）只是 demo 场景名残留。

- `[V]` `odometerHandler.Start()`（RVA `0x19FD970`）**无条件**调用
  `setGameAndGraphicsVariables`：`0x19ffb60` 的 `cbz w9, #0x19ffb9c` 目标地址
  正是 `0x19ffb9c`（汇合点），而 `bl #0x1a02c44` 紧跟其后。

### 1.3 主菜单场景没有 `odometerHandler`（关键）

- `[V]` 实机：**冷启动进主界面锁 30，玩过一局退回主菜单才是高帧率**。
  用户补充：**原生设 60 时同样如此** ⇒ 这是游戏自身行为，不是模块引入。

- `[?]` 推断（未反汇编定位）：首次启动那条更早的 30 fps 设置路径尚未找到。
  但 `odometerHandler` 只存在于赛道场景，所以「进主菜单后没有人再设一次」成立。

- `[V]` **修法不是改回调逻辑**（回调压根没被调用），而是
  「提前安装 hook」+「安装后主动补调一次」。

### 1.4 存档污染风险（决定了实现形态）

- `[V]` `saveFile` 的写入点只有两处：
  `settingsHandler.acceptNewValues`（设置页确认）与
  `odometerHandler.OnDestroy`（**退出赛道**，RVA `0x1A0307C`，`0x1a032e4` 尾跳）。

- `[V]` `BuildPlayerSettings_V2`(0x189EFB0) 把 `GlobalVariables.sV` 的**全部字段**
  逐条 `BinaryWriter.Write`，包括 `+0x20`。

- ⇒ **任何写 `sV->_graphicsFramerate` 的方案都会在用户退一次赛道后永久污染存档**，
  且 `OnDestroy` 的守卫（`cmp w8,w9` 比较摄像机字段 `0x3c0` vs `0x3dc`）与帧率无关，
  拦不住这次写入。

### 1.5 vsync 不是约束

- `[V]` `dump.cs` 的 `QualitySettings` 类**没有 `vSyncCount` 成员**（IL2CPP 构建期裁剪）；
  全 dump `grep -i vsync` 仅命中无关的 `ProfilerCategoryColor.VSync`。
- `[V]` `data.unity3d → QualitySettings` 全部 8 个质量档的 `vSyncCount` 都是 0。
- `[V]` Unity 官方：Android/iOS 上 `vSyncCount` **恒被忽略**，Unity 2021+ 恒按
  `vsync=0` 处理，只用 `targetFrameRate`。本作是 Unity **2022.3.62f3**。
- ⇒ `targetFrameRate = 120` 不会被 vsync 吞掉。

### 1.6 但只解决软件层（重要边界）

- `[V]` Unity 文档：「Rendering is always limited by the maximum refresh rate of the display」。
- `[V]` 游戏窗口的 `WindowManager.LayoutParams.preferredRefreshRate` = **0**
  （= 无偏好；Unity 构造 LayoutParams 时不设该字段，AOSP 注释
  「A value of {@code 0} means no preference」）。
- ⇒ 屏幕停在 60Hz 时，`targetFrameRate=120` 只会让渲染线程被 buffer queue 阻塞，
  **用户体感无明显改善**。实测在目标设备上已跑到高帧率，说明屏幕本身给了高刷新率。

---

## 2. 依据（研究）

### 2.1 `set_targetFrameRate` 是 ICall thunk，不是托管方法

`[V]` 函数体（RVA `0x3257B6C`，共 **56 字节**，尾跳不返回）：

```
0x3257b6c:  str   x30, [sp, #-0x20]!
0x3257b70:  stp   x20, x19, [sp, #0x10]
0x3257b74:  adrp  x20, #0x390c000        ← ★ PC 相对寻址
0x3257b78:  ldr   x1,  [x20, #0xba0]     ; 读 ICall 缓存槽
0x3257b7c:  mov   w19, w0                ; 保存 value
0x3257b80:  cbnz  x1,  #0x3257b98        ; 已解析 → 快路径
0x3257b84:  adrp  x0,  #0x954000
0x3257b88:  add   x0,  x0, #0xb4
0x3257b8c:  bl    #0x16427ec             ; il2cpp_resolve_icall
0x3257b90:  mov   x1,  x0
0x3257b94:  str   x0,  [x20, #0xba0]     ; 写缓存
0x3257b98:  mov   w0,  w19               ; 恢复 value
0x3257b9c:  ldp   x20, x19, [sp, #0x10]
0x3257ba0:  ldr   x30, [sp], #0x20
0x3257ba4:  br    x1                     ; 尾跳 ICall（不 ret）
```

`[V]` `.rodata 0x9540b4` 解出的符号名 = `UnityEngine.Application::set_targetFrameRate`。

**ABI 结论**（三处交叉验证）：

| 项 | 结论 |
|---|---|
| 参数 | `w0 = value`，`x1 = method_info`（静态方法，**无 this**） |
| `method_info` 是否有意义 | **否** —— 5 个调用点全部 `mov x1, xzr` 传 NULL，且函数体第一件事就把 x1 覆盖成 ICall 缓存指针 |
| proxy 写法 | 用两参版本 `(int value, void *method_info)`，原样透传；**不得读 x1 做判断** |

### 2.2 为什么不写字段

| 缺陷 | 机制 |
|---|---|
| 污染存档 | `odometerHandler.OnDestroy` → `savePlayerSettings` → `BuildPlayerSettings_V2` 把 `sV` 全字段写回 |
| UI 错乱 | `loadSettingsToMenu` 执行 `TMP_Dropdown.set_value(2)`，而移动端 `m_Options.Count == 2`，越界值行为无保证（clamp 到 1 ⇒ 显示 60 实际跑 120） |
| 被覆盖 | `acceptNewValues` 用下拉框 `m_Value`（≤1）写回 `sV+0x20`，冲掉模块的 2 |

### 2.3 设置项持久化形态

- `[V]` 不是 PlayerPrefs，是二进制存档 `Application.persistentDataPath + "/saveFile"`
  （`SaveLoadManager.fileName` 常量）。格式为 `MemoryStream + BinaryWriter` **顺序写**，
  `saveVersion` 校验。**帧率是第 5 个 int**（顺序 `0x10, 0x14, 0x28, 0x18, 0x20, ...`）。
- `[V]` 图形设置**没有任何 PlayerPrefs key**；全 `.so` 可确认的 key 只有
  `speedUnit` 与 `actualControl`。

### 2.4 设置页回填与提交链

- `[V]` 回填（`settingsHandler.loadSettingsToMenu` @ `0x1A28874`）：
  `ldr x0,[x19,#0x88]`（graph_fps）→ `ldr w1,[x8,#0x20]`（sV 值）→ `TMP_Dropdown.set_value`。
- `[V]` 提交（`settingsHandler.acceptNewValues` @ `0x1A28EE4`）：
  `ldr w8,[x8,#0x130]`（Dropdown.m_Value）→ `str w8,[x20,#0x20]`（写新 sV）。
  此处 sV 来自 `settingsValues..ctor` 返回值（**新建对象整体替换**，不是原地改）。
- `[V]` `graph_fps` 不在 `set_interactable(false)` 的禁用列表里 ⇒ 帧率下拉始终可选。

---

## 3. 实现（固化）

### 3.1 改动清单

| 文件 | 内容 |
|---|---|
| `native/src/fps_unlock_hook.c` / `.h` | 新建：hook + proxy + 补设 + 运行时开关 |
| `native/CMakeLists.txt` | 加入 `src/fps_unlock_hook.c` |
| `native/src/ala_core.c` | `initFpsUnlock` / `setForce120Fps` JNI |
| `app/.../offsets/OffsetTable.kt` | `APPLICATION_SET_TARGET_FRAME_RATE = 0x3257B6CL` |
| `app/.../NativeBridge.kt` | `initFpsUnlock` / `setForce120Fps` + Safe 包装 |
| `app/.../config/ModConfig.kt` | key `enable_force_120_fps`、默认 `false`、四处构造点 |
| `app/.../config/ConfigReceiver.kt` | 运行时同步 |
| `app/.../AlaMobileModule.kt` | **早期 deferred 块**安装；15s 块只同步运行时开关 |
| `app/.../ui/viewmodel/ConfigViewModel.kt` | UiState 字段 + setter |
| `app/.../ui/screen/configure/ConfigurePagerMiuix.kt` | 开关项（DLC 卡加载下方） |
| `app/.../ui/CustomIcons.kt` | `FrameRateIcon`（用户提供 SVG，viewBox `1 1 22 22`） |

### 3.2 核心逻辑

```c
// 单一事实源：install 期取初值，运行时只判它（不要 `|| g_config.enable_*`）
static volatile int g_force_120_active = 0;

#define FPS_SRC_LOW  30
#define FPS_SRC_HIGH 60
#define FPS_TARGET   120

static void proxy_set_target_frame_rate(int value, void *method_info) {
    typedef void (*t)(int, void *);
    int out = value;
    if (g_force_120_active && (value == FPS_SRC_LOW || value == FPS_SRC_HIGH)) {
        out = FPS_TARGET;
    }
    if (out != value) LOGI("fps_unlock: targetFrameRate %d -> %d", value, out);
    ((t) g_set_fps_orig)(out, method_info);
}
```

**只匹配 30/60**，不用区间判据：未来若出现 90 档，白名单不会误改。

### 3.3 两个补设时机（缺一不可）

| 时机 | 解决什么 |
|---|---|
| **安装后立即补设** | 首次启动那次 30 在 hook 装上之前就设完了 ⇒ 纠正已错的值 |
| **运行时打开开关时补设** | 开着游戏切开关，不必等下次进赛道才生效 |

补设只在 `g_force_120_active` 为真时执行，关掉时逐字节等于原版。

传 `NULL` 作 `method_info` 安全 —— `0x3257b78` 会立刻用它覆盖 x1。

### 3.4 日志指纹

| 日志 | 含义 |
|---|---|
| `Hooked UnityEngine.Application.set_targetFrameRate at 0x...` | hook 安装成功 |
| `shadowhook_hook_sym_addr(set_targetFrameRate) failed: <errno>` | 安装失败（先查 PC 相对重定位） |
| `fps_unlock: targetFrameRate 30 -> 120` | proxy 改写生效 |
| `fps_unlock: applied targetFrameRate(120)` | 补设执行 |

### 3.5 升版核对清单

1. `find_callers.py 0x3257b6c` 仍为 **5 个且全为 `bl`**（若内联化，该路径绕过 hook）。
2. `.rodata 0x9540b4` 的符号名仍是 `UnityEngine.Application::set_targetFrameRate`。
3. 函数前 4 条指令仍含 `adrp 0x390c000`（PC 相对，ShadowHook 需重定位）。
4. `setGameAndGraphicsVariables` 三处 `mov w0,#0x1e/#0x3c/#0x78` 立即数未变。
5. `sV+0x20` 仍是 `_graphicsFramerate`（settingsValues 字段偏移未重排）。

---

## 4. 已否决的方案 — 不要再试

- `[X]` **直接写 `GlobalVariables.sV->_graphicsFramerate = 2`**
  —— 存档永久污染（`odometerHandler.OnDestroy` 隐式存盘）+ 设置页显示错乱
  （下拉框只有 2 项，`set_value(2)` 越界）+ 会被 `acceptNewValues` 冲掉。
  三个缺陷都无法规避。

- `[X]` **在 `setGameAndGraphicsVariables` 之前抢写字段**
  —— 挡不住设置页「确认」那条写回路径，也挡不住 `SettingsChangeFromPause`
  事件重跑。窗口存在但不完整。

- `[X]` **hook `setGameAndGraphicsVariables` 再裸调一次 `set_targetFrameRate`**
  —— 比改入参多一层依赖（要解析 methodInfo 或裸调 RVA），且调用次数变两次。
  唯一优势场景（`_graphicsFramerate` 为 0/1/2 之外的值）在移动端不可达。

- `[X]` **改 Android 层 `preferredRefreshRate` 代替改 IL2CPP**
  —— 两层正交：屏幕 120Hz 而 `targetFrameRate` 仍 60 时，每帧重复显示两次，
  流畅度不变。

- `[X]` **NDK `ANativeWindow_setFrameRate`**
  —— Unity 已通过 `dlopen("libandroid.so") + dlsym` 实现并在 `SwappyGL::swap`
  里调用；重复实现无益且可能打架。

- `[X]` **把 `initFpsUnlock` 放 15s 延迟块**（第一版实现踩到）
  —— 首次启动那次 30 fps 在 15s 块之前就设完了，hook 漏掉。
  实机现象：「刚开游戏进主界面锁 30，玩过再回主菜单才是高帧率」。
  注意这**不是**模块引入的问题：用户原生设 60 时同样如此。

---

## 5. 待验证

- `[?]` **首次启动那条更早的 30 fps 设置路径**尚未反汇编定位。
  当前靠「安装后补设」覆盖，功能上已解决；但如果那条路径在运行期会再触发，
  补设可能被覆盖。目前实测未见复发。
- `[?]` **Swappy 是否启用**：`boot.config` 无 `androidUseSwappy`，Unity 默认值
  文献矛盾（Unity 员工称默认关，社区称 2020.1+ 默认开）。
  判定法：`logcat | grep -i swappy`，出现 `Swappy is disabled.` 即未启用。
  *只影响是否需要做刷新率那层，不影响本功能。*
- `[?]` **LTPO 机型上「主动请求高刷新率」是否会被厂商调度器压制**。
  项目已有 OriginOS 4 定案（overlay 高频 invalidate 扰动刷新率仲裁，
  120/72/60/51/45/30 乱跳），但该定案**只对 OriginOS 4 成立**；
  MEIZU 20（Flyme）与一加平板 2 Pro（ColorOS）全库零记录。
  若将来要做刷新率层，必须先实测。
- `[?]` **Weak 设备上的表现**：本作 `DynamicResolution` 是禁用状态
  （`Enable()` 全 `.so` 零调用者，`PlatformSupported` 恒 false），
  没有自动降分辨率兜底 ⇒ 性能不足时可能 40~50 横跳、反而更差。
  这是「开关 + 默认关」的实质理由。
