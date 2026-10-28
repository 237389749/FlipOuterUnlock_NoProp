package com.example.flipunlock.hook.util

/**
 * Feature toggles via SystemProperties. All default to true (enabled).
 *
 * ★2026-09-19 开关精简: 只保留「改动会翻转可见行为、出问题需要一键回退」的开关 + 总开关。
 *   纯优化项不再单独设开关, 改为**常开、仅受总开关 `enable` 控制**:
 *     app.whitelist / app.intercept / ime / systemui.flashlight /
 *     ui.controlcenter / ui.notifmenu / ui.recentsmenu
 *   已弃用并移除: display.state(DisplayStateHook 未注册, 文件保留供回退实验)。
 *
 * ★2026-10-28 `ui.widget` 恢复为可关 + 与全屏**联动**（反馈: "关闭全屏后小部件没有恢复"）:
 *   `WidgetRemove` 把 fliphome 的 refreshWindow(ADD) 全改写成 REMOVE → 小部件窗口永不添加,
 *   而"全屏"是另一套机制(AppFullscreen 改 window flag/letterbox), 关全屏不会恢复小部件;
 *   精简时又把 ui.widget 并进常开项 → 用户**没有任何手段**恢复原生小部件。
 *   设计定案（用户 2026-10-28）: 全屏与小部件接管**是一体的** ——
 *   小部件不移除时那块区域仍拦截触摸, 全屏只是"摆设"; 即"小部件存在 ⇒ 必然不需要全屏"。
 *   → 唯一闸门 `fullscreenActive = displayFullscreen && uiWidget`（任一为 false 即整组关闭）:
 *     · display.fullscreen=false → 全屏关 + 小部件恢复（可见 + 可触摸）✓
 *     · ui.widget=false          → 小部件恢复 + **全屏也关**（不允许"全屏但小部件挡触摸"的无意义组合）
 *     · 两者都 true(默认)        → 全屏 + 小部件接管, 与历史行为完全一致
 *
 * List all keys and current values:
 *   getprop | grep persist.flipunlock
 *
 * Set before reboot:
 *   setprop persist.flipunlock.enable false               # master kill switch
 *   setprop persist.flipunlock.display.aod false          # outer-screen AOD (AodHook)
 *   setprop persist.flipunlock.display.cutout false       # cutout 全清 (CutoutZeroHook + CutoutAlwaysHook)
 *   setprop persist.flipunlock.display.fullscreen false   # force fullscreen (AppFullscreen)
 *   setprop persist.flipunlock.ui.widget false            # 不移除/不接管外屏小部件(恢复原生小部件)
 *   setprop persist.flipunlock.devicestate.pin true       # DeviceState 钉死(单外屏, §44.9) —— ★默认 false
 *   setprop persist.flipunlock.devicestate.value 4        # 目标 state(默认 4; 0/1/4=外屏单屏, 禁用 5/6)
 * 恢复默认: setprop <key> ""   (空值 = 默认 true; 例外: devicestate.pin 默认 false)
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
        "persist.flipunlock.ui.widget",
        "persist.flipunlock.devicestate.pin",
        "persist.flipunlock.devicestate.value",
    )

    // Master switch —— 所有 hook(含常开的优化项)统一受它控制
    val enabled: Boolean get() = raw("persist.flipunlock.enable", true)

    // Display —— 保留开关: 这三项直接改变可见行为(挖孔/全屏/外屏 AOD), 需要能单独回退
    val displayAod: Boolean get() = enabled && raw("persist.flipunlock.display.aod", true)
    val displayCutout: Boolean get() = enabled && raw("persist.flipunlock.display.cutout", true)
    val displayFullscreen: Boolean get() = enabled && raw("persist.flipunlock.display.fullscreen", true)

    // 外屏小部件接管（WidgetRemove 移除窗口 / WidgetTouchPassthrough 触摸透传）
    // ★2026-10-28 联动（用户定案）: 全屏与小部件接管是**一体**的 ——
    //   小部件不移除时那块区域仍然拦截触摸, 全屏就只是"摆设"; 反过来说"小部件存在 ⇒ 必然不需要全屏"。
    //   ⇒ 两个 property **任一为 false 都等于"回到原生外屏"**, 整组一起关闭:
    //     · display.fullscreen=false → 全屏关 + 小部件恢复
    //     · ui.widget=false          → 小部件恢复 + 全屏也关（避免"全屏但小部件挡触摸"的无意义组合）
    //     · 两者都 true(默认)        → 全屏 + 小部件接管, 与历史行为完全一致
    val uiWidget: Boolean get() = enabled && raw("persist.flipunlock.ui.widget", true)

    /** 全屏与小部件接管的唯一有效闸门（两者联动, 不可分离）。 */
    val fullscreenActive: Boolean get() = displayFullscreen && uiWidget

    // DeviceState 钉死(单外屏专用, refMD §44.9) —— 把物理上报的折叠态固定成"外屏单屏态"
    // ★默认关闭: 属实验性固定(与属性 4 原生布局叠加), 需要时显式 setprop 开启
    // 值只能是外屏单屏态 0/1/4(默认 4, 不带 TRIGGER_SLEEP); 5/6(双屏)会致 applyLayoutLocked NPE
    val deviceStatePin: Boolean get() = enabled && raw("persist.flipunlock.devicestate.pin", false)
    val deviceStatePinValue: Int get() = rawInt("persist.flipunlock.devicestate.value", 4)

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

    private fun rawInt(key: String, default: Int): Int {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("getInt", String::class.java, Int::class.javaPrimitiveType!!)
                .invoke(null, key, default) as? Int ?: default
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
