package com.example.flipunlock.hook.system_server

import android.content.ComponentName
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 外屏启动拦截解除 —— 走**新版本装载路径**（2026-11-16）。
 *
 * ── NoProp 为什么需要它（而 unlock2 不需要）─────────────────────────────────
 * 拦截的总闸是 `ContinuityPolicyService.sFolded`：
 *   `isInterceptList(ComponentName) = sFolded ? isInterceptListUnCheckFold(...) : false`
 *   · unlock2 = 身份 1（伪装普通手机）→ `sFolded` 恒 **false** → 拦截**本来就不发生**，
 *     那边再 hook 也没意义（所以 unlock2 里默认关闭）。
 *   · NoProp  = **属性 4（flip 原生）**     → 合盖时 `sFolded` 为 **true** → 拦截真的会发生，
 *     这正是本文件存在的理由。
 *
 * ── 4.0.3 上原来那条路为什么是死的 ──────────────────────────────────────────
 * `AppInterceptCacheFix` 的 ② 直接 `param.classLoader.loadClass("…InterceptActivityController")`。
 * 4.0.3 起这个类**不在主 classpath**，而是由 `MiuiStubRegistry` 从独立 jar 动态加载：
 *
 *     // com.android.server.wm.MiuiAppContinuityLoader（在 services.jar，主 classpath）
 *     CLASS_PATH = "/system_ext/framework/miui-appcompat.appcontinuity.jar"
 *     ClassLoader cl = MiuiStubRegistry.loadJar(CLASS_PATH, true, <loader>);
 *     MiuiStubRegistry.collectImpl("com.android.server.wm.MiuiAppContinuityImplCollector", cl);
 *
 * ⇒ 主 classloader 看不到它（parent-first，child 的类对 parent 不可见），loadClass 必失败。
 * 本文件的做法：hook `MiuiStubRegistry.loadJar` / `collectImpl` **捕获那个 child ClassLoader**，
 * 再在它上面装 hook。
 *
 * ── 逻辑方向（反编译确认，别弄反）───────────────────────────────────────────
 *     if (!isInterceptListUnCheckFold(component)) return;   // ← 不拦截
 *     ... moveToBack + StartActivityTipView.show()          // ← 拦截（"展开到内屏继续操作"）
 * ⇒ hook 成 **false** = 早退 = 不拦截。
 *
 * 另注：`AppCompatTask`（缓存闸门 `hasActivityInterceptionKey`）**仍在 services.jar**，
 * `AppInterceptCacheFix` 的 ① 在 4.0.3 是有效的，保留不用动。本 hook 生效后，
 * `AppWhitelist` 那套「每开机给 400 个包重放 allowstart」就变成冗余兜底了（暂不删）。
 *
 * 进程: system_server
 * 开关: persist.flipunlock.app.intercept（默认 true；关掉 = 回到只靠 allowstart 白名单）
 */
object InterceptUnlock {

    private const val CLS_CONTROLLER = "com.android.server.wm.InterceptActivityController"
    private const val CLS_STUB_REGISTRY = "com.miui.base.MiuiStubRegistry"
    private const val CLS_CONTINUITY_LOADER = "com.android.server.wm.MiuiAppContinuityLoader"
    private const val JAR_MARK = "appcontinuity"

    @Volatile
    private var installed = false

    fun hook(param: SystemServerStartingParam) {
        if (!Config.appIntercept) {
            log("InterceptUnlock: DISABLED by persist.flipunlock.app.intercept")
            return
        }
        log("InterceptUnlock: setting up (新版本动态 jar 路径)")

        if (!captureNewPathLoaders()) {
            log("InterceptUnlock: 未命中新版本装载器 -> 退回主 classloader（老版本路径）")
        }
        installOn(param.classLoader, source = "param.classLoader")
        runCatching { Class.forName(CLS_CONTROLLER) }.getOrNull()?.let {
            installOn(it.classLoader, source = "Class.forName")
        }
    }

    /**
     * 新版本路径：抓 `MiuiStubRegistry.loadJar(String, boolean, ClassLoader)` 的返回值
     * （= 该 jar 的 child ClassLoader）。
     */
    private fun captureNewPathLoaders(): Boolean {
        val reg = runCatching { Class.forName(CLS_STUB_REGISTRY) }.getOrNull()
        if (reg == null) {
            log("InterceptUnlock: $CLS_STUB_REGISTRY 不可加载 -> 老版本，无动态 jar")
            return false
        }
        var hookedAny = false

        runCatching {
            val m = reg.method(
                "loadJar",
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
                ClassLoader::class.java,
            )
            hook(m) { chain ->
                val result = chain.proceed()
                val path = chain.args.getOrNull(0) as? String
                if (result is ClassLoader && path != null && path.contains(JAR_MARK)) {
                    log("InterceptUnlock: 捕获 jar ClassLoader via loadJar($path)")
                    installOn(result, source = "loadJar")
                }
                result
            }
            hookedAny = true
            log("InterceptUnlock: ✓ $CLS_STUB_REGISTRY.loadJar hooked")
        }.onFailure { log("InterceptUnlock: loadJar hook failed: ${it.message}") }

        runCatching {
            val m = reg.method("collectImpl", String::class.java, ClassLoader::class.java)
            hook(m) { chain ->
                (chain.args.getOrNull(1) as? ClassLoader)?.let { installOn(it, source = "collectImpl") }
                chain.proceed()
            }
            hookedAny = true
            log("InterceptUnlock: ✓ $CLS_STUB_REGISTRY.collectImpl hooked")
        }.onFailure { log("InterceptUnlock: collectImpl hook failed: ${it.message}") }

        runCatching {
            val loaderCls = Class.forName(CLS_CONTINUITY_LOADER)
            val init = loaderCls.method("init")
            hook(init) { chain ->
                val r = chain.proceed()
                runCatching { Class.forName(CLS_CONTROLLER) }.getOrNull()
                    ?.let { installOn(it.classLoader, source = "MiuiAppContinuityLoader.init") }
                r
            }
            hookedAny = true
            log("InterceptUnlock: ✓ $CLS_CONTINUITY_LOADER.init hooked")
        }.onFailure { log("InterceptUnlock: continuity loader hook failed: ${it.message}") }

        return hookedAny
    }

    private fun installOn(cl: ClassLoader, source: String) {
        if (installed) return
        val cls = runCatching { cl.loadClass(CLS_CONTROLLER) }.getOrNull()
        if (cls == null) {
            log("InterceptUnlock: [$source] $CLS_CONTROLLER 不可加载")
            return
        }
        val m = runCatching { cls.method("isInterceptListUnCheckFold", ComponentName::class.java) }
            .getOrElse {
                log("InterceptUnlock: [$source] isInterceptListUnCheckFold 找不到: ${it.message}")
                return
            }
        // 2026-11-23: 裸 hook 会从 MiuiStubRegistry.loadJar 链把异常带进 system_server 启动期；
        // 失败只记日志、不置 installed，留给后续 classloader 重试。
        val ok = runCatching {
            hook(m, replaceResult(false))
            true
        }.getOrElse {
            log("InterceptUnlock: [$source] hook isInterceptListUnCheckFold failed: ${it.message}")
            false
        }
        if (!ok) return
        installed = true
        log(
            "InterceptUnlock: ✓ [$source] ${cls.name}.isInterceptListUnCheckFold → false" +
                " (loader=${cl.javaClass.simpleName})"
        )
    }
}
