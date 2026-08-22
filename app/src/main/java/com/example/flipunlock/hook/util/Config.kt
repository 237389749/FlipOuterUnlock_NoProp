package com.example.flipunlock.hook.util

/**
 * Feature toggles via SystemProperties. All default to true (enabled).
 *
 * List all keys and current values:
 *   getprop | grep persist.flipunlock
 *
 * Set before reboot:
 *   setprop persist.flipunlock.enable false               # master kill switch
 *   setprop persist.flipunlock.display.aod false          # outer-screen AOD (AodHook)
 *   setprop persist.flipunlock.display.cutout false       # cutout 全清 (CutoutZeroHook + CutoutAlwaysHook)
 *   setprop persist.flipunlock.display.fullscreen false   # force fullscreen (AppFullscreen)
 *   setprop persist.flipunlock.app.whitelist false        # app whitelist (AppWhitelist)
 *   setprop persist.flipunlock.ime false                  # IME freedom (InputMethodHook + SogouInputHook)
 *   setprop persist.flipunlock.systemui.flashlight false  # flashlight (FlashlightHook)
 *   setprop persist.flipunlock.ui.controlcenter false     # control center (ControlCenterHook)
 *   setprop persist.flipunlock.ui.notifmenu false         # notification menu (NotifMenuFixHook)
 *   setprop persist.flipunlock.ui.widget false            # widget overlay removal (WidgetRemove + WidgetTouchPassthrough)
 *   setprop persist.flipunlock.ui.recentsmenu false       # recents cache (RecentsCacheFix)
 *
 * 注(2026-08-21): NoProp 为属性 4(flip 原生)版本 —— 无身份伪装/旋转/音量/壁纸/续接
 *   hook(旧项目属性 1 方案已排除)。下表 keys 仅列当前实际注册的 hook 开关。
 */
object Config {
    private val keys = listOf(
        "persist.flipunlock.enable",
        "persist.flipunlock.display.aod",
        "persist.flipunlock.display.cutout",
        "persist.flipunlock.display.fullscreen",
        "persist.flipunlock.app.whitelist",
        "persist.flipunlock.ime",
        "persist.flipunlock.systemui.flashlight",
        "persist.flipunlock.ui.controlcenter",
        "persist.flipunlock.ui.notifmenu",
        "persist.flipunlock.ui.widget",
        "persist.flipunlock.ui.recentsmenu",
        "persist.flipunlock.display.state",
    )

    // Master switch
    val enabled: Boolean get() = raw("persist.flipunlock.enable", true)

    // Display
    val displayAod: Boolean get() = enabled && raw("persist.flipunlock.display.aod", true)
    val displayCutout: Boolean get() = enabled && raw("persist.flipunlock.display.cutout", true)
    val displayFullscreen: Boolean get() = enabled && raw("persist.flipunlock.display.fullscreen", true)
    // DisplayStateHook(DeviceState 布局按 state 分支) — 2026-08-19 已注释禁用(Main 未注册),
    //   属性 4 原生 display 布局已正确; 开关保留供未来回退实验。
    val displayState: Boolean get() = enabled && raw("persist.flipunlock.display.state", true)

    // App
    val appWhitelist: Boolean get() = enabled && raw("persist.flipunlock.app.whitelist", true)

    // IME
    val ime: Boolean get() = enabled && raw("persist.flipunlock.ime", true)

    // SystemUI
    val flashlight: Boolean get() = enabled && raw("persist.flipunlock.systemui.flashlight", true)
    val uiControlCenter: Boolean get() = enabled && raw("persist.flipunlock.ui.controlcenter", true)
    val uiNotifMenu: Boolean get() = enabled && raw("persist.flipunlock.ui.notifmenu", true)

    // fliphome
    val uiWidget: Boolean get() = enabled && raw("persist.flipunlock.ui.widget", true)
    val uiRecentsMenu: Boolean get() = enabled && raw("persist.flipunlock.ui.recentsmenu", true)

    /** Print all toggle keys and values. */
    fun logConfig() {
        val sb = StringBuilder("═══ FlipOuterUnlock Config ═══\n")
        for (key in keys) {
            sb.append("  $key = ${readProp(key)}\n")
        }
        sb.append("  (getprop | grep persist.flipunlock)")
        log(sb.toString())
    }

    private fun raw(key: String, default: Boolean): Boolean {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!)
                .invoke(null, key, default) as? Boolean ?: default
        } catch (_: Exception) {
            default
        }
    }

    private fun readProp(key: String): String {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as? String ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
