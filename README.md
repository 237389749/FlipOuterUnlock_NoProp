# FlipOuterUnlock_NoProp — MIX Flip 外屏模块（无属性层版）

> LSPosed module for Xiaomi MIX Flip — **属性 4（flip 原生）形态**：不伪装手机、不需要 KSU 属性层，
> 保持 fliphome 原生外屏桌面。集 90833c4 / unlock2 / Lite / 262 各项目之力，只保留属性 4 下真实有用的修复。

**One-liner**: cutout 全清全局全屏、禁用 size-compat letterbox、外屏 AOD（内屏多样式）、
外屏 IME 自由、手电筒直开、控制中心/通知菜单修复、fliphome 小部件三件套。

[English](#english) | [中文](#chinese)

---

<a name="english"></a>
## English

### Features (by hook)

**Display & Fullscreen**
- **Cutout 全清（双端）** — `CutoutZeroHook`（system_server 源头清零：`calculateDisplayCutoutForRotation→NO_CUTOUT` + `pathAndDisplayCutoutFromSpec` + `InsetsState.getDisplayCutoutSafe` + `fromResourcesRectApproximation`）+ `CutoutAlwaysHook`（全进程非 null 空 cutout 构造 → 全局全屏，含相机防御）
- **Force fullscreen** — `AppFullscreen`（禁用 MIUI flip size-compat letterbox，app 端 `getLayoutInDisplayCutoutMode→ALWAYS`）

**App**
- `AppWhitelist` — outer-screen app launch whitelist（allowstart 放行）

**IME & Input**
- `InputMethodHook` — outer-screen IME freedom（`shouldShowCurrentInput→true`、旋转 toast 抑制）
- `SogouInputHook` — Sogou IME layout fixes

**AOD（outer-screen always-on display）**
- `AodHook` — **dual-device** (flip1 + flip2/HyperOS4). Outer-screen AOD shows **inner-screen multi-style clock**（`isFlipped→false` 切断 FlipLinkage 样式替换 — flip1 修"外屏专用样式", flip2/OS4 修"**设置里选了样式却不生效**"; `MiuiFullAodManager.fullAodEnable→false` 切断"锁屏时钟+黑"）; 亮屏（`setDozeScreenState {1,3,4}→2`，**flip1 only** — flip2 原生 state 4 可亮, 抬屏只会全亮度+改亮度语义）; `DozeLifecycleOwner.initState` 崩溃防崩（`MiuiDozeService.onCreate` 补装, 修 SystemUI 崩溃环）

**SystemUI**
- `FlashlightHook` — flashlight flip-prompt bypass + direct toggle
- `ControlCenterHook` — flip control-center COMPACT edit button（移植 MixFlipMod, unlock2 重写）
- `NotifMenuFixHook` — outer-screen notification menu in normal-phone style

**fliphome**
- `WidgetRemove` — cover-screen widget overlay removal
- `RecentsCacheFix` — recents cache refresh
- `WidgetTouchPassthrough` — widget touch passthrough

### Hook Architecture

```
onSystemServerStarting (system_server):
├── CutoutZeroHook         ← cutout 源头清零（4 层, refMD DisplayCutout §16-18）
├── AppFullscreen          ← size-compat letterbox 禁用
├── AppWhitelist           ← 外屏 app 白名单
├── InputMethodHook        ← IME 自由
└── AodHook.hookFramework  ← AOD framework 侧（双机型入口; #1/#2 已 DISABLED）

onPackageReady:
├── CutoutAlwaysHook [*]         ← 全进程非 null 空 cutout → 全局全屏（相机防御）
├── AodHook [systemui/aod]       ← 外屏 AOD 多样式 + FlipLinkage 切断（flip2 修"样式选择不生效"; 亮屏 hook 仅 flip1）
├── FlashlightHook [systemui]    ← 手电筒直开
├── ControlCenterHook [systemui] ← 控制中心 COMPACT 编辑按钮
├── NotifMenuFixHook [systemui]  ← 通知菜单普通样式
├── SogouInputHook [sogou]       ← 输入法修复
├── WidgetRemove [fliphome]      ← 小部件移除
├── RecentsCacheFix [fliphome]   ← 最近任务缓存
└── WidgetTouchPassthrough [fliphome] ← 小部件触摸透传
```

### Feature Toggles

开关已精简（2026-09-19）：只保留「改动会翻转可见行为、需要能单独回退」的 4 个 + 总开关；
纯优化项（白名单 / 拦截缓存 / IME / 手电筒 / 控制中心 / 通知菜单 / 小部件 / 最近任务）
**常开、仅受 `enable` 控制**，不再单独设 property（`reboot` 后生效, 无 UI）:

```bash
getprop | grep persist.flipunlock          # list
setprop persist.flipunlock.display.aod false && reboot   # example
setprop persist.flipunlock.display.aod ""                # 恢复默认(true)
```

| Property | Default | Controls |
|----------|---------|----------|
| `persist.flipunlock.enable` | true | **Master switch**（关掉 = 全部 hook 停用，含常开优化项）|
| `persist.flipunlock.display.aod` | true | Outer-screen AOD (AodHook) |
| `persist.flipunlock.display.cutout` | true | Cutout 全清 (CutoutZeroHook + CutoutAlwaysHook) |
| `persist.flipunlock.display.fullscreen` | true | Force fullscreen (AppFullscreen) |

常开（无独立 property）: AppWhitelist / AppInterceptCacheFix / InputMethodHook + SogouInputHook /
FlashlightHook / ControlCenterHook / NotifMenuFixHook / WidgetRemove + WidgetTouchPassthrough / RecentsCacheFix。

### LSP Scope

system, systemui, aod, camera, fliphome, sogou, miuihome, gallery

### Requirements / Build

- LSPosed（libxposed API 101+）、Xiaomi MIX Flip、HyperOS
- `./gradlew :app:assembleDebug`；CI：push `main` 自动构建

### Credits & License

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) — ControlCenter/NotifMenu 移植参考
- `refMD/cleaned/` — MIUI 反编译分析（`AOD_Full_Chain.md` = AOD 全貌权威文档）
- AGPL-3.0

---

<a name="chinese"></a>
## 中文

### 功能（按 hook 列）

**显示与全屏**
- **Cutout 全清（双端）** — `CutoutZeroHook`（system_server 源头清零 4 层）+ `CutoutAlwaysHook`（全进程非 null 空 cutout 构造 → 全局全屏，含相机防御）
- **强制全屏** — `AppFullscreen`（禁用 MIUI flip size-compat letterbox）

**应用**
- `AppWhitelist` — 外屏应用启动白名单（allowstart）

**输入法**
- `InputMethodHook` — 外屏 IME 自由（`shouldShowCurrentInput→true`、旋转 toast 抑制）
- `SogouInputHook` — 输入法布局修复

**AOD（外屏息屏显示）**
- `AodHook` — **双机型**（flip1 + flip2/HyperOS4）。外屏 AOD 显示**内屏多样式时钟**（`isFlipped→false` 切断 FlipLinkage 样式替换 —— flip1 修"外屏专用样式"，flip2/OS4 修"**设置里选了样式却不生效**"；`MiuiFullAodManager.fullAodEnable→false` 根治"锁屏时钟+黑"）；亮屏（`setDozeScreenState {1,3,4}→2`，**仅 flip1** —— flip2 原生 state 4 即可亮屏，抬成 2 只会全亮度 ON + 改 doze 亮度语义）；`DozeLifecycleOwner.initState` 崩溃防崩（`MiuiDozeService.onCreate` 补装，修 SystemUI 崩溃环）

**SystemUI**
- `FlashlightHook` — 手电筒翻转提示绕过 + 直接 toggle
- `ControlCenterHook` — flip 控制中心 COMPACT 编辑按钮（移植 MixFlipMod，unlock2 重写）
- `NotifMenuFixHook` — 外屏通知菜单普通手机样式

**fliphome**
- `WidgetRemove` — 外屏桌面小部件移除
- `RecentsCacheFix` — 最近任务缓存刷新
- `WidgetTouchPassthrough` — 小部件触摸透传

### Hook 架构

```
onSystemServerStarting (system_server):
├── CutoutZeroHook         ← cutout 源头清零（4 层, refMD DisplayCutout §16-18）
├── AppFullscreen          ← size-compat letterbox 禁用
├── AppWhitelist           ← 外屏 app 白名单
├── InputMethodHook        ← IME 自由
└── AodHook.hookFramework  ← AOD framework 侧（双机型入口; #1/#2 已 DISABLED）

onPackageReady:
├── CutoutAlwaysHook [*]         ← 全进程非 null 空 cutout → 全局全屏（相机防御）
├── AodHook [systemui/aod]       ← 外屏 AOD 多样式 + FlipLinkage 切断（flip2 修"样式选择不生效"；亮屏 hook 仅 flip1）
├── FlashlightHook [systemui]    ← 手电筒直开
├── ControlCenterHook [systemui] ← 控制中心 COMPACT 编辑按钮
├── NotifMenuFixHook [systemui]  ← 通知菜单普通样式
├── SogouInputHook [sogou]       ← 输入法修复
├── WidgetRemove [fliphome]      ← 小部件移除
├── RecentsCacheFix [fliphome]   ← 最近任务缓存
└── WidgetTouchPassthrough [fliphome] ← 小部件触摸透传
```

### 功能开关

开关已精简（2026-09-19）：只保留「改动会翻转可见行为、需要能单独回退」的 4 个 + 总开关；
纯优化项（白名单 / 拦截缓存 / IME / 手电筒 / 控制中心 / 通知菜单 / 小部件 / 最近任务）
**常开、仅受 `enable` 控制**，不再单独设 property（重启生效，无 UI）：

```bash
getprop | grep persist.flipunlock          # 查看
setprop persist.flipunlock.display.aod false && reboot   # 示例
setprop persist.flipunlock.display.aod ""                # 恢复默认(true)
```

| 属性 | 默认 | 控制 |
|------|------|------|
| `persist.flipunlock.enable` | true | **总开关**（关掉 = 全部 hook 停用，含常开优化项）|
| `persist.flipunlock.display.aod` | true | 外屏 AOD (AodHook) |
| `persist.flipunlock.display.cutout` | true | Cutout 全清 (CutoutZeroHook + CutoutAlwaysHook) |
| `persist.flipunlock.display.fullscreen` | true | 强制全屏 (AppFullscreen) |

常开（无独立 property）：AppWhitelist / AppInterceptCacheFix / InputMethodHook + SogouInputHook /
FlashlightHook / ControlCenterHook / NotifMenuFixHook / WidgetRemove + WidgetTouchPassthrough /
RecentsCacheFix。

### LSP 作用域

system, systemui, aod, camera, fliphome, sogou, miuihome, gallery

### 要求 / 构建

- LSPosed（libxposed API 101+）、Xiaomi MIX Flip、HyperOS
- `./gradlew :app:assembleDebug`；CI：push `main` 自动构建

### 致谢 / License

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) — ControlCenter/NotifMenu 移植参考
- `refMD/cleaned/` — MIUI 反编译分析（`AOD_Full_Chain.md` = AOD 全貌权威文档）
- AGPL-3.0
