package com.example.flipunlock.hook.util

/**
 * Centralized exclusion registry for packages that need original device behavior.
 *
 * 注(2026-08-21): NoProp 为属性 4(flip 原生)版本 —— 无身份伪装 hook(DeviceIdentity/
 *   ScreenType/TinyScreenFix 均已排除, 属性 4 原生就是 flip 身份)。本表服务于
 *   AppFullscreen 等对"真实身份"的依赖: 以下包保持原生 isFlipDevice 等判定。
 *
 * WHY EACH EXCLUSION:
 *   SystemUI — lock screen panel (TinyKeyguardPanelViewController) needs
 *              isFlipDevice=true for correct tiny-screen layout
 *   Sogou IME — keyboard layout reads flip identity / cutout data
 */
object Exclusions {

    const val SYSTEMUI = "com.android.systemui"
    const val SOGOU_IME = "com.sohu.inputmethod.sogou.xiaomi"

    /** Packages that must see real flip identity (isFlipDevice, isTinyScreen, etc.) */
    val DEVICE_IDENTITY = setOf(SYSTEMUI, SOGOU_IME)

    /** Packages that must see real display cutout for correct layout (保留, 当前无引用) */
    val GLOBAL_CUTOUT = setOf(SOGOU_IME)
}
