package com.example.flipunlock

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.cutout.CutoutAlwaysHook
import com.example.flipunlock.hook.fliphome.RecentsCacheFix
import com.example.flipunlock.hook.fliphome.WidgetRemove
import com.example.flipunlock.hook.fliphome.WidgetTouchPassthrough
import com.example.flipunlock.hook.ime.SogouInputHook
import com.example.flipunlock.hook.system_server.AppFullscreen
import com.example.flipunlock.hook.system_server.AppWhitelist
import com.example.flipunlock.hook.system_server.DisplayStateHook
import com.example.flipunlock.hook.system_server.InputMethodHook
import com.example.flipunlock.hook.system_server.WallpaperFixHook
import com.example.flipunlock.hook.systemui.AodHook
import com.example.flipunlock.hook.systemui.ControlCenterHook
import com.example.flipunlock.hook.systemui.FlashlightHook
import com.example.flipunlock.hook.systemui.NotifMenuFixHook
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

/**
 * 无属性层版本（2026-08-17 新建, FlipOuterUnlock_NoProp）。
 *
 * 定位: 属性 4(flip 原生)形态的通用模块 —— 不伪装手机、不需要 KSU 属性层,
 * 保持 fliphome 原生外屏桌面方案。集 90833c4/unlock2/lite/262 各项目之力,
 * 只保留属性 4 下真实有用的修复。
 *
 * 组织: 一文件一功能, 按目标应用目录聚合(工作准则 §5/§6):
 *   system_server/   system_server 进程(拓扑/全屏/壁纸/白名单/输入法/AOD框架侧)
 *   systemui/        com.android.systemui 进程(AOD/手电筒/磁贴/通知)
 *   fliphome/        com.miui.fliphome(外屏桌面小部件/最近任务)
 *   ime/             com.sohu.inputmethod.sogou.xiaomi(输入法)
 *   cutout/          全进程 cutout 构造(双机型全屏, 排除 camera 保真实 cutout)
 *
 * 排除(2026-08-17 定稿): 身份伪装系(DeviceIdentity/ScreenType/TinyScreenFix/
 *   Flip1AodIdentity/CameraReverse)、CameraFixHook(属性4无用)、SFDeviceGestureHook
 *   (fliphome 方案不需要 miuihome 手势)、RotationFix/VolumeKeyRemap(属性4原生自带)、
 *   CutoutRemove/Flip2CutoutLetterbox(属性1方案, 由 CutoutAlwaysHook 替代)、
 *   SystemUiKeyguardFix(属性4原生正常)、LauncherRoute(改默认桌面)。
 */
class Main : XposedModule() {

    // ── App-process hooks (onPackageReady) ──────────────────────────
    // 每个条目=一个完整功能; dispatch 按 targetPackages 匹配
    // ("*" = 通配, 只首次触发取 framework classloader)。
    private val packageHooks = listOf<BaseHook>(
        CutoutAlwaysHook,           // cutout: 全进程空 cutout 构造 → 全局全屏(双机型; camera 排除保真实 cutout)
        FlashlightHook,             // systemui: 手电筒(跳弹窗 + setFlipListening 直接 toggle)
        ControlCenterHook,          // systemui: flip 控制中心 COMPACT 编辑按钮 + device center 尺寸(移植 MixFlipMod, unlock2 重写)
        NotifMenuFixHook,           // systemui: 外屏通知菜单按普通手机样式(移植 MixFlipMod hookNotification, 补全逻辑链)
        AodHook,                    // systemui: flip1 AOD 外屏显示(属性4版, 已去 #5 getCutout→NONE)
        SogouInputHook,             // ime: 输入法键盘高度/布局修复
        WidgetRemove,               // fliphome: 外屏桌面小部件移除
        RecentsCacheFix,            // fliphome: 最近任务缓存
        WidgetTouchPassthrough,     // fliphome: 外屏桌面小部件触摸透传
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        log("Main: onModuleLoaded — process=${currentProcessName()}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting")
        DisplayStateHook.hook(param)   // 拓扑钉死: flip1 内屏已拆恒外屏 / flip2 折叠外屏
        AppFullscreen.hook(param)      // size-compat 禁用(全屏)
        WallpaperFixHook.hook(param)   // 壁纸右黑修复(flip1 外屏, 原生 bug)
        AppWhitelist.hook(param)       // 外屏 app 白名单(全部 app setForceDisplayCompatMode allowstart)
        InputMethodHook.hook(param)    // IME 外屏自由(shouldShowCurrentInput→true / 转屏 toast 抑制)
        AodHook.hookFramework(param)   // AOD framework 侧(flip1; flip2 内部 SKIP)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage} proc=${currentProcessName()}")
        AppFullscreen.hookApp(param)   // app 端全屏(size-compat 禁用, 双机型)
        packageHooks.forEach { hook ->
            val isWildcard = hook.targetPackages.contains("*")
            val isTargeted = hook.targetPackages.contains(param.packageName)
            if (!isWildcard && !isTargeted) return@forEach
            // "*" hooks 用第一个包的 classloader(framework 类), 后续包跳过避免重复 hook
            if (isWildcard && !param.isFirstPackage) return@forEach
            log("Main: loading ${hook.javaClass.simpleName} for ${param.packageName}")
            hook.hook(param)
        }
    }
}
