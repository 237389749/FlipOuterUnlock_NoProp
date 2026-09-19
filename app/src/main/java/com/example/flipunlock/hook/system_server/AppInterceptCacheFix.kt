package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 拦截判定缓存修复（2026-09-18, 国际版 HyperOS3 实测 + gl-services / gl-miui-appcompat 反编译）
 *
 * 现象：装了模块、也跑了放行命令，仍有部分 app 无法启动；且「先直接点开（被拦一次）→ 再跑
 *   放行命令」的 app 永远打不开（用户实测确认）。
 *
 * 根因（反编译实锤）：
 *   InterceptActivityController.startActivityInnerBlockedWithSnapShot()
 *     [gl-miui-appcompat.appcontinuity/sources/com/android/server/p000wm/InterceptActivityController.java:798]
 *       if (appCompatTask.hasActivityInterceptionKey(componentName))             ← ★缓存优先
 *           isInterceptList = appCompatTask.isActivityInterception(componentName)
 *       else { isInterceptList = isInterceptList(cn); setActivityInterception(cn, ...); }
 *   AppCompatTask.ACTIVITY_INTERCEPTION_MAP 是 private static
 *     [gl-services/sources/com/android/server/p020wm/AppCompatTask.java:27]，
 *   仅在 AppCompatTask.destroy() 中 clear()（同文件 :59）。
 *   → app 被拦过一次就 put(cn, true) 进 static Map；之后任何放行（wm dump / 模块 enroll）都被
 *     缓存抢先命中旧值 → 永远打不开。force-stop 不清（实测），只能重启（static Map 随
 *     system_server 重启才清空）。
 *
 * 修复：hook AppCompatTask.hasActivityInterceptionKey(String) → false
 *   → 缓存永不命中 → 每次走 isInterceptList(cn) → isInterceptListUnCheckFold
 *     → 其第 1 步读 LOCAL_POLICY_BY_COMMAND[pkg]=="allowstart"（AppWhitelist 已 enroll）→ false 放行
 *   （② 判定层兜底为可选双保险；类不在 BOOTCLASSPATH，失败不影响 ①）
 *
 * 依赖：AppWhitelist（提供 allowstart enroll）；开关：无(2026-09-19 精简 —— 常开, 仅受 persist.flipunlock.enable 控制)
 * 进程：system_server
 */
object AppInterceptCacheFix {

    private const val APP_COMPAT_TASK = "com.android.server.wm.AppCompatTask"
    private const val INTERCEPT_CTRL = "com.android.server.wm.InterceptActivityController"

    fun hook(param: SystemServerStartingParam) {
        // 开关精简(2026-09-19): 纯优化项不再单独设开关, 只受总开关控制
        if (!Config.enabled) {
            log("AppInterceptCacheFix: DISABLED by persist.flipunlock.enable")
            return
        }
        log("AppInterceptCacheFix: setting up")
        safeHook("AppInterceptCacheFix") {
            // ── ① 核心：缓存绕过 AppCompatTask.hasActivityInterceptionKey(String) → false ──
            //    类在 services.jar(gl-services) → BOOTCLASSPATH 内, 加载无障碍
            runCatching {
                val cls = param.classLoader.loadClass(APP_COMPAT_TASK)
                val m = cls.method("hasActivityInterceptionKey", String::class.java)
                hook(m, replaceResult(false))
                log("AppInterceptCacheFix: ✓ hasActivityInterceptionKey → false (缓存绕过生效)")
            }.onFailure { log("AppInterceptCacheFix ① hasActivityInterceptionKey failed: ${it.message}") }

            // ── ② 兜底(可选双保险)：isInterceptListUnCheckFold(ComponentName) → false ──
            //    类在 miui-appcompat.appcontinuity.jar(非 BOOTCLASSPATH) → 多 classloader 尝试
            runCatching {
                val cls = loadAnyClass(param.classLoader, INTERCEPT_CTRL)
                    ?: throw ClassNotFoundException(INTERCEPT_CTRL)
                val cnCls = param.classLoader.loadClass("android.content.ComponentName")
                val m = cls.method("isInterceptListUnCheckFold", cnCls)
                hook(m, replaceResult(false))
                log("AppInterceptCacheFix: ✓ isInterceptListUnCheckFold → false (判定层放行)")
            }.onFailure { log("AppInterceptCacheFix ② isInterceptListUnCheckFold failed (①仍有效): ${it.message}") }
        }
    }

    /** 多 classloader 尝试加载目标类(可能在非 BOOTCLASSPATH 的 miui-appcompat.appcontinuity.jar)。 */
    private fun loadAnyClass(fallback: ClassLoader, name: String): Class<*>? {
        runCatching { return fallback.loadClass(name) }
        var cl: ClassLoader? = fallback
        while (cl != null) {
            runCatching { return cl.loadClass(name) }
            cl = cl.parent
        }
        runCatching { return Thread.currentThread().contextClassLoader?.loadClass(name) }
        return null
    }
}
