package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * DeviceState 钉死（单外屏方案专用）—— 把"外屏单屏态"写成物理上报值。
 *
 * 逻辑链（AOSP DeviceStateManagerService，refMD §44/§44.9）:
 *   xiaomi.sensor.flip_status → HAL → DeviceStateProviderImpl
 *     → DeviceStateManagerService$DeviceStateProviderListener.onStateChanged(int)  ← ★ 本 hook 点
 *       → setBaseState → mBaseState
 *         → updatePendingStateLocked() → stateToConfigure
 *           → LogicalDisplayMapper.setDeviceStateLocked → applyLayoutLocked
 *             → DeviceStateToLayoutMap.get(state) → 外屏 enable / 内屏 disable
 *
 * 为什么必须打在这一层（2026-09-20 flip1 实测，refMD §44.9）:
 *   1. 外屏只有在它是 logical displayId 0(defaultDisplay) 时才出图:
 *      state 0/1/4 → 外屏; 2/3 → 内屏; 5/6 → 双屏(state 5 外屏仅 follow, 实测全黑)
 *   2. 一次折叠态事件(传感器抖动/HAL 误判)可把外屏打成 HWC BAD_DISPLAY
 *      (SDM "Composition strategies exhausted for display = 87-0"), 且极难自愈
 *   3. 只固定 layout 不够: `cmd device_state state 0`(override 钉死, layout 不变)
 *      下物理态一变照样 756 次 BadDisplay; 只有固定"状态机输入(Base)"才有效
 *      —— 实测 `cmd device_state base-state 4` 抗物理晃动, Committed 保持 4、0 次 BadDisplay
 *   4. state 4(OPENED_REVERSE) = OUTER_PRIMARY + FOLD_IN_CLOSED 且**不带
 *      POWER_CONFIGURATION_TRIGGER_SLEEP**（0/1 带 → 折叠触发休眠, §44.8 H4）→ 更适合常驻
 *
 * 实现: hook 物理上报入口 onStateChanged(int) → 换成目标 state(默认 4),
 *   让状态机"认为物理态恒为外屏单屏态" → 不再产生 0↔2 的 layout / display 电源转换
 *   → 外屏不再被切走、也不再被打成 BadDisplay。
 *
 * ⚠️ 注意:
 *   - 目标 state 只能是"外屏单屏态" 0 / 1 / 4; 绝不能用 5/6(双屏):
 *     内屏 LogicalDisplay 不存在 → applyLayoutLocked NPE(android.display 崩, LSP 安全模式)
 *     —— 见 DisplayStateHook.kt 头注
 *   - 同理本 hook 不打 DeviceStateToLayoutMap.get(那是 display 层, 挡不住 BadDisplay;
 *     90833c4 的该实现在本机实测未生效)
 *   - state 4 带 EMULATED_ONLY / APP_INACCESSIBLE: 对 app_accessible 类消费点的副作用待实测
 *   - flip1 上 system_server 侧 logcat 常打不出(§41.2: 只是没日志, 并非断路);
 *     验证以 `adb shell cmd device_state state`(Base 应为目标 state) + 行为(晃动不黑)为准
 *
 * 开关（默认关闭, 需显式开启）:
 *   setprop persist.flipunlock.devicestate.pin true     # 启用
 *   setprop persist.flipunlock.devicestate.value 4      # 目标 state（默认 4；0=CLOSED, 1=TENT, 4=OPENED_REVERSE）
 *   setprop persist.flipunlock.devicestate.pin ""       # 关闭（空值 = 默认 false）
 *
 * 进程: system_server
 */
object DeviceStatePin {

    private const val SVC = "com.android.server.devicestate.DeviceStateManagerService"
    private const val LISTENER = "$SVC\$DeviceStateProviderListener"

    fun hook(param: SystemServerStartingParam) {
        if (!Config.deviceStatePin) {
            log("DeviceStatePin: DISABLED by persist.flipunlock.devicestate.pin")
            return
        }
        val target = Config.deviceStatePinValue
        log("DeviceStatePin: setting up → state $target")
        safeHook("DeviceStatePin") {
            hookProviderListener(param.classLoader, target)
        }
    }

    /** 物理上报入口: DeviceStateProviderListener.onStateChanged(int) → target */
    private fun hookProviderListener(cl: ClassLoader, target: Int) {
        val cls = runCatching { cl.loadClass(LISTENER) }.getOrNull()
            ?: runCatching {
                cl.loadClass(SVC).declaredClasses
                    .firstOrNull { it.simpleName.contains("ProviderListener") }
            }.getOrNull()
        if (cls == null) {
            log("DeviceStatePin: ✗ DeviceStateProviderListener not found")
            return
        }
        val m = cls.method("onStateChanged", Int::class.javaPrimitiveType!!)
        var logged = false
        hook(m) { chain ->
            val from = chain.args[0] as? Int
            if (!logged) {
                log("DeviceStatePin: onStateChanged $from → $target")
                logged = true
            }
            chain.proceed(arrayOf<Any?>(target))
        }
        log("DeviceStatePin: ✓ hooked ${cls.name}#onStateChanged(int) → $target")
    }
}
