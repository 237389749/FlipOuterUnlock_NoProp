package com.example.flipunlock.hook.util

/**
 * Feature toggles via SystemProperties. All default to true (enabled).
 *
 * ★2026-09-19 开关精简: 只保留「改动会翻转可见行为、出问题需要一键回退」的开关 + 总开关。
 *   纯优化项不再单独设开关, 改为**常开、仅受总开关 `enable` 控制**:
 *     app.whitelist / app.intercept / ime / systemui.flashlight /
 *     ui.controlcenter / ui.notifmenu / ui.widget / ui.recentsmenu
 *   已弃用并移除: display.state(DisplayStateHook 未注册, 文件保留供回退实验)。
 *
 * List all keys and current values:
 *   getprop | grep persist.flipunlock
 *
 * Set before reboot:
 *   setprop persist.flipunlock.enable false               # master kill switch
 *   setprop persist.flipunlock.display.aod false          # outer-screen AOD (AodHook)
 *   setprop persist.flipunlock.display.cutout false       # cutout 全清 (CutoutZeroHook + CutoutAlwaysHook)
 *   setprop persist.flipunlock.display.fullscreen false   # force fullscreen (AppFullscreen)
 * 恢复默认: setprop <key> ""   (空值 = 默认 true)
 *
 * 注(2026-08-21): NoProp 为属性 4(flip 原生)版本 —— 无身份伪装/旋转/音量/壁纸/续接
 *   hook(旧项目属性 1 方案已排除)。
 */
object Config {
    private val keys = listOf(
        "persist.flipunlock.enable",
        "persist.flipunlock.display.aod",
        "persist.flipunlock.display.cutout",
        "persist.flipunlock.display.fullscreen",
    )

    // Master switch —— 所有 hook(含常开的优化项)统一受它控制
    val enabled: Boolean get() = raw("persist.flipunlock.enable", true)

    // Display —— 保留开关: 这三项直接改变可见行为(挖孔/全屏/外屏 AOD), 需要能单独回退
    val displayAod: Boolean get() = enabled && raw("persist.flipunlock.display.aod", true)
    val displayCutout: Boolean get() = enabled && raw("persist.flipunlock.display.cutout", true)
    val displayFullscreen: Boolean get() = enabled && raw("persist.flipunlock.display.fullscreen", true)

    /** Print all toggle keys and values. */
    fun logConfig() {
        val sb = StringBuilder("═══ FlipOuterUnlock Config ═══\n")
        for (key in keys) {
            sb.append("  $key = ${readProp(key)}\n")
        }
        sb.append("  (未列出的优化项常开; 全部受 persist.flipunlock.enable 控制)\n")
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
