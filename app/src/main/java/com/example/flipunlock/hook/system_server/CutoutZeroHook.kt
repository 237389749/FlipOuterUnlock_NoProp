package com.example.flipunlock.hook.system_server

import android.view.DisplayCutout
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * system_server 端 cutout 清零（2026-08-19, 补 app 端 CutoutAlwaysHook 的不足）。
 *
 * 背景（refMD DisplayCutout.md §17, 2025-07-26 实锤）:
 *   app 端 CutoutAlwaysHook(Display.getCutout→空) 只解决 app 进程读取;
 *   system_server 侧 WindowManager 布局/InsetsState 仍用原始 cutout:
 *     - dumpsys window displays 显示 display0 外屏 cutoutSpec={...@bind_right_cutout} 398px 仍在
 *     - BoundsCompatUtils.getCompatGravity / fillInsetsState **直接读字段**
 *       dc.mDisplayInfo.displayCutout(FIELD 非 getter → getDisplayInfo/getDisplayCutout hook 无效)
 *   → 挖孔避让/黑边/内容偏移仍在(app 全屏但布局仍按 398px 避让)。
 *
 * 核心 hook（refMD hook 点 #9, THE choke point in system_server）:
 *   DisplayContent.calculateDisplayCutoutForRotation(int rotation)
 *     → mDisplayInfo.displayCutout 的唯一设置源(SINGLE SOURCE):
 *       this.mDisplayInfo.displayCutout = displayCutout.isEmpty() ? null : displayCutout
 *     → 返回 NO_CUTOUT(empty) → 字段永为 null → 全链路(含 dumpsys/InsetsState/兼容重力)都空。
 *
 * 开关: persist.flipunlock.display.cutout(与 app 端 CutoutAlwaysHook 共用)。
 * 进程: system_server。
 */
object CutoutZeroHook {

    /** DisplayCutout.NO_CUTOUT(@hide, 编译期不可见) — 反射获取。 */
    private val noCutout: Any? by lazy {
        runCatching {
            val f = DisplayCutout::class.java.getDeclaredField("NO_CUTOUT")
            f.isAccessible = true
            f.get(null)
        }.getOrNull()
    }

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayCutout) {
            log("CutoutZero: DISABLED by persist.flipunlock.display.cutout")
            return
        }
        log("CutoutZero: setting up (system_server 端 cutout 源头清零)")
        safeHook("CutoutZero") {
            // ★ DisplayContent.calculateDisplayCutoutForRotation(int) → NO_CUTOUT
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayContent")
                val m = cls.method("calculateDisplayCutoutForRotation",
                    Int::class.javaPrimitiveType!!)
                val empty = noCutout ?: return@runCatching
                hook(m, replaceResult(empty))
                log("CutoutZero: ✓ calculateDisplayCutoutForRotation → NO_CUTOUT (mDisplayInfo.displayCutout 恒 null)")
            }.onFailure { log("CutoutZero: calculateDisplayCutoutForRotation failed: ${it.message}") }

            // 防御: DisplayCutout.pathAndDisplayCutoutFromSpec(9参, 所有 cutout 路径汇聚, refMD hook 点 #1)
            //   → Pair(null, NO_CUTOUT) 跳过整个解析管线(覆盖 calculateDisplayCutoutForRotation 之外的路径)
            runCatching {
                val dcClass = param.classLoader.loadClass("android.view.DisplayCutout")
                val method = dcClass.declaredMethods.firstOrNull {
                    it.name == "pathAndDisplayCutoutFromSpec" && it.parameterCount == 9
                } ?: return@runCatching
                method.isAccessible = true
                val pairClass = param.classLoader.loadClass("android.util.Pair")
                val pairCtor = pairClass.getConstructor(Any::class.java, Any::class.java)
                val empty = noCutout ?: return@runCatching
                hook(method) { _ ->
                    pairCtor.newInstance(null, empty)
                }
                log("CutoutZero: ✓ pathAndDisplayCutoutFromSpec → (null, NO_CUTOUT)")
            }.onFailure { log("CutoutZero: pathAndDisplayCutoutFromSpec failed: ${it.message}") }
        }
    }
}
