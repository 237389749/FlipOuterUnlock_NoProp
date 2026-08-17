package com.example.flipunlock.hook.systemui

import android.content.Context
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 外屏通知菜单按普通手机样式渲染（2026-08-17 移植 MixFlipMod hookNotification, 补全逻辑链）。
 *
 * 逻辑链（FlipRes b5c1-systemui 反编译 + refMD FoldState §43.6.4 延伸记录）:
 *   MiuiNotificationMenuRow 构造/更新(110/231/321 行) → createMenuViews(boolean)
 *     → 内部读 MiuiConfigs.isTinyScreen(Context) 决定菜单样式(tiny 简化菜单 / 普通全菜单)
 *   属性 4 外屏: isTinyScreen=true(原生 tiny 身份) → 通知长按菜单按 tiny 简化渲染
 *   优化: createMenuViews 调用期间 MiuiConfigs.isTinyScreen→false → 菜单按普通手机样式
 *   (更多操作项; 与 unlock2 ControlCenterHook 同源: MixFlipMod SystemUIHook 移植,
 *    仅取"通知菜单"部分, 独立成文件)。
 *
 * 实现: 标志位作用域(isTinyScreen 仅在 createMenuViews 调用窗口返回 false, 同线程 main,
 *   不影响其他读 isTinyScreen 的路径), 非全局改值。
 *
 * 进程: com.android.systemui（LSPosed 2.0.1 该进程以 pkg=android 回调, 需进程守卫）。
 * 开关: persist.flipunlock.ui.notifmenu（默认 true）。
 */
object NotifMenuFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

    @Volatile
    private var inCreateMenu = false

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.uiNotifMenu) return
        // 进程守卫: pkg=android 时确认在 systemui 进程(同 ControlCenterHook)
        if (param.packageName == "android") {
            val proc = currentProcessName()
            if (proc != "com.android.systemui") {
                log("NotifMenuFix: skip, process=$proc")
                return
            }
        }
        safeHook("NotifMenuFix") {
            val cl = processClassLoader(param.classLoader)
            runCatching {
                val configsCls = cl.loadClass("com.miui.utils.configs.MiuiConfigs")
                hook(configsCls.method("isTinyScreen", Context::class.java)) { chain ->
                    if (inCreateMenu) false else chain.proceed()
                }
                val menuCls = cl.loadClass(
                    "com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow")
                hook(menuCls.method("createMenuViews", Boolean::class.javaPrimitiveType!!)) { chain ->
                    inCreateMenu = true
                    try {
                        chain.proceed()
                    } finally {
                        inCreateMenu = false
                    }
                }
                log("NotifMenuFix: ✓ createMenuViews 窗口内 isTinyScreen→false(外屏通知菜单普通样式)")
            }.onFailure { log("NotifMenuFix failed: ${it.message}") }
        }
    }
}
