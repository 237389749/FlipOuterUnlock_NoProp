package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Always-On Display on the outer (cover) screen when folded.
 *
 * AOD is the most tangled feature in this module (refMD: FoldState_Device_Identity.md
 * §25 AOD/Doze architecture, §26 AOD app layer, §30.6 属性4 终版判定链; DisplayCutout.md §28
 * 正常折叠 AOD 启用 + cutout 崩溃链). Two cooperating sides:
 *
 * ── Framework side (system_server) — keeps the rear dream alive ──
 *   PowerManagerService.handleRearSandman(groupId=1):
 *     if (!mRearAlwaysOnEnabled && wakefulness==3) → sleepPowerGroup (NO AOD)
 *   #1 updateRearDozeSettings(groupId, alwaysOn, isFullAod): force alwaysOn+isFullAod
 *      for groupId 1 so handleRearSandman starts the dream instead of sleeping.
 *   #2 DreamController.stopDream(force, reason): block the "slow to connect"/
 *      "slow to finish" timeout kills for groupId 1 (the AOD dream connects slowly
 *      and would otherwise be torn down after ~5s).
 *   NOTE: MiuiFlipPolicy/DisplayManagerServiceImpl.shouldDeviceBeSleep()→false are
 *   deliberately NOT hooked — blocking sleep prevents the dream from ever starting
 *   (the old project commented them out for exactly this reason).
 *
 * ── App side (com.android.systemui / com.miui.aod) — force the AOD screen state ──
 *   The AOD classes (com.miui.aod.*) live in MIUIAod.apk under a SEPARATE classloader
 *   that onPackageReady's classLoader cannot see. Three layers:
 *   Layer 0 (framework, visible from SystemUI — runs FIRST, defuses the NPE):
 *     AOD cutout defense: com.miui.aod.util.DisplayUtils.getCutoutPosition(Context)
 *     → Direction.CAMERA_CUTOUT_ON_NONE. 实锤(FlipRes/aod DisplayUtils.java:18-29):
 *     `cutout = context.getDisplay().getCutout(); cutout.getBoundingRectLeft()...`
 *     无 null 检查。调用点全包唯一 = DozeHost.dealWithFlipChange L796(startDozing L601,
 *     refMD §28.2 崩溃链)。若 getCutout()==null → NPE → SystemUI crash-loop(每~7s)。
 *     ⚠ 与 CutoutAlwaysHook #2 的分工: #2 是全进程全局 hook Display.getCutout→非 null 空
 *     cutout(主防线, 但属性 4 下会波及 TinyKeyguardPanel, 见下"KNOWN RISKS"); 本 hook
 *     是 AOD 内精准防御(第二道, 不动全局 getCutout)。
 *   Layer 1 (framework DreamService, visible from SystemUI):
 *     #3 DreamService.setDozeScreenState(int): 全状态 {0,1,3,4} → 2 (ON 亮屏).
 *        flip1 实测(1ae7af5): DOZE_AOD(4) 方案物理屏不显示, 2(亮屏 ON) 必亮 ——
 *        AOD 内容渲染在亮屏状态。0=FINISH 1=DOZE 2=ON/PULSING 3=DOZE_SUSPEND 4=DOZE_AOD.
 *     #4 DreamService.onDreamingStarted(): one-shot trigger for Layer 2.
 *   Layer 2 (runtime, via the DozeMachine instance's OWN classloader):
 *     walk the object graph from the DreamService to find com.miui.aod.doze.DozeMachine,
 *     then with its classloader hook DozeMachine.requestState() (redirect
 *     DOZE/DOZE_SUSPEND/FINISH → DOZE_AOD), DozeService.setDozeScreenState() (same map
 *     as #3), DozeHost.isFullAod()→false, and FlipLinkageStyleController
 *     isFlipped()→false / isUsingFlip()→true.
 *     isFlipped→false(属性4版 v2, 2026-08-21): refMD §30 —— 用户需求: 外屏 AOD 显示
 *     "内屏正常多样式息屏"(非 FlipLinkage 外屏简单时钟/萌宠)。判定链:
 *     isAodEnable = isFlipDevice && isFlipped → isUsingFlip(FlipLinkage 外屏样式)
 *                  : isAodSettingsEnabled()          ← 内屏标准多样式路径(目标)
 *     isUsingLinkageStyle = isUsingFlip && isFlipped  ← hook false 后 = false, 不走 FlipLinkage。
 *     hook false 同时保证: L792 `!isFlipped()&&!z` 恒 false → isFlipping 恒 false
 *     → 跳过 setAodVisibility(false)+600ms PAUSING 振荡(§30.3 不稳定源 1 被绕开)。
 *     isUsingFlip→true 保留: kill switch(DozeMachine.resolveIntermediateState) +
 *     dealWithFlipChange L806 PAUSING 存活分支 保活。
 *     设置侧佐证: aod 设置页默认 full_screen_aod_on=1(息屏样式=和锁屏样式一致),
 *     设计上外屏 AOD 本就走标准样式, FlipLinkage 是 flip 系列专属(萌宠等)。
 *
 * KNOWN RISKS (refMD §26/§28.4):
 *   - classloader 隔离: onPackageReady 的 classLoader 看不到 com.miui.aod.*。本版
 *     层 B 用多 classloader 候选(进程 Application / 回调 classLoader / ActivityThread
 *     mAllClassLoaders 遍历), 并在 Layer 2(拿到 DozeMachine 的 classloader)后补装。
 *     若仍装不上, 依赖 CutoutAlwaysHook #2(非 null 空 cutout)兜底 —— 2026-08-20 设备
 *     实测: 属性 4 原生外屏 cutout 运行时即为空(非 null 空实例), AOD 稳定不崩, 说明
 *     Display.getCutout() 在属性 4 下本就返回非 null(空), NPE 只在源头真变 null 时出现。
 *   - DozeMachine state flow 可跳过 DOZE_AOD; Layer 2 图遍历/classloader 可能部分不触发。
 *   - ⚠ SystemUI 崩溃(2026-08-21, dropbox 8-19 起 14 次): DozeLifecycleOwner.initState
 *     IllegalStateException "no event up from DESTROYED" —— static 单例 registry 在 dream
 *     FINISH(ON_DESTROY) 后停在 DESTROYED, dream 进程内重建时 initState 无法恢复。
 *     修复: hook initState, DESTROYED → 反射 mState=INITIALIZED(本文件 hookDozeLifecycleOwnerInitState,
 *     层 B + L2 双时机)。
 *   - ⚠ 层 A(CutoutAlwaysHook #2) 全进程全局 Display.getCutout→空 cutout 在属性 4 下
 *     可能波及 TinyKeyguardPanel(2026-08-17 实测 hook Display.getCutout→NONE → 构造 NPE
 *     崩溃环; refMD §28.4-2)。本 hook 的"精准修"定位即避免该波及 —— 但层 A 若开启,
 *     全局 getCutout 已被替换, 精准修仅作为第二道保险, 二者叠加行为需装机实测。
 *   - FlipLinkageClock 视觉(§28.4-4): getClockOrientation() 驱动时钟旋转/挖孔避让
 *     margin(FlipLinkageClock.java:596)。hook NONE = 时钟按无挖孔布局。2026-08-20 设备
 *     实测: 属性 4 原生 cutout 为空 → 原生 getCutoutPosition 本就返回 NONE → 与 hook 结果
 *     一致, 无新增视觉变化。
 *
 * Toggle: persist.flipunlock.display.aod (default true)
 */
object AodHook : BaseHook() {

    // "android" is required because LSPosed v2.0.1 only fires onPackageReady("android")
    // in the systemui process — onPackageReady("com.android.systemui") is never called
    // despite the package being in scope. The process guard in setupHooks() ensures the
    // app-side hooks only install in systemui / miui.aod, not system_server or other apps.
    override val targetPackages = listOf("android", "com.android.systemui", "com.miui.aod")

    /** Runtime (Layer 2) hooks installed at most once per process. */
    @Volatile
    private var runtimeHooksInstalled = false

    /** 层 B (getCutoutPosition → NONE) 已装标志 + 实际安装到的 classloader.
     *  记录 loader 而非纯布尔: 多个 classloader 可能各有一份 com.miui.aod.* 类,
     *  L2 拿到真实 plugin classloader(machineCl) 后若与此不同仍需补装(否则被
     *  错误的"已装"跳过, review 2026-08-20 should-fix #1)。 */
    @Volatile
    private var aodCutoutDefenseCl: ClassLoader? = null

    /** DozeLifecycleOwner.initState 防崩 hook 已装的 classloader 集合(幂等, 同 loader 只装一次). */
    private val initStateHookedCls = mutableSetOf<ClassLoader>()

    // ── Framework side (system_server) ──────────────────────────────────

    fun hookFramework(param: SystemServerStartingParam) {
        // 2026-08-14 机型 gate: flip2 AOD 正常显示, 不需要本 hook(OS3.1 AOD 逻辑不同)
        if (isFlip2Device()) {
            log("AodHook(framework): SKIP (flip2 AOD 正常)")
            return
        }
        if (!Config.displayAod) {
            log("AodHook: DISABLED by persist.flipunlock.display.aod")
            return
        }
        log("AodHook(framework): setting up")
        safeHook("AodHook") {
            hookUpdateRearDozeSettings(param.classLoader)
            hookStopDream(param.classLoader)
        }
    }

    // ── #1 PowerManagerService.updateRearDozeSettings → alwaysOn + isFullAod ──
    private fun hookUpdateRearDozeSettings(classLoader: ClassLoader) {
        runCatching {
            val pms = classLoader.loadClass("com.android.server.power.PowerManagerService")
            val method = pms.method(
                "updateRearDozeSettings",
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!)
            hook(method) { chain ->
                val groupId = chain.args[0] as? Int
                if (groupId == 1) {
                    // getArgs() is immutable — rewrite via proceed(Object[])
                    chain.proceed(arrayOf<Any?>(1, true, true))
                } else {
                    chain.proceed()
                }
            }
            log("AodHook: #1 updateRearDozeSettings(groupId=1) → alwaysOn+fullAod")
        }.onFailure { log("AodHook: #1 updateRearDozeSettings failed", it) }
    }

    // ── #2 DreamController.stopDream → block timeout kills for groupId 1 ──
    private fun hookStopDream(classLoader: ClassLoader) {
        runCatching {
            val dcClass = classLoader.loadClass("com.android.server.dreams.DreamController")
            val method = dcClass.getDeclaredMethod(
                "stopDream", Boolean::class.javaPrimitiveType!!, String::class.java)
            method.isAccessible = true
            hook(method) { chain ->
                val reason = chain.args[1] as? String
                val groupId = runCatching { chain.thisObject.getField("mGroupId") as? Int }.getOrNull()
                if (groupId == 1 && (reason == "slow to connect" || reason == "slow to finish")) {
                    log("AodHook: #2 BLOCKED stopDream('$reason') for groupId 1")
                    return@hook null
                }
                chain.proceed()
            }
            log("AodHook: #2 DreamController.stopDream guarded")
        }.onFailure { log("AodHook: #2 stopDream failed", it) }
    }

    // ── App side (SystemUI / com.miui.aod) ──────────────────────────────

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.displayAod) return
        // 2026-08-14 机型 gate: flip2 AOD 正常显示, 不需要本 hook
        if (isFlip2Device()) {
            log("AodHook(app): SKIP (flip2 AOD 正常)")
            return
        }
        // When pkg="android", only proceed if we're actually inside systemui / miui.aod.
        // This avoids installing app-side hooks in system_server or unrelated app processes.
        if (param.packageName == "android") {
            val proc = currentProcessName()
            if (proc != "com.android.systemui" && proc != "com.miui.aod") {
                log("AodHook(app): skip, process=$proc")
                return
            }
            log("AodHook(app): pkg=android but process=$proc — installing app hooks")
        } else {
            log("AodHook(app): setupHooks pkg=${param.packageName}")
        }
        safeHook("AodHook") {
            // ── 层 B: AOD NPE 防御(2026-08-19 引入, 2026-08-20 重写加固) ──
            // CutoutZero(system_server, DisplayCutout §28.2)清源头 → mDisplayInfo.displayCutout
            //   = null → app 进程 Display.getCutout() 返回 null(framework Display.java:383-384)
            //   → AOD DisplayUtils.getCutoutPosition 读 getBoundingRectLeft() 无 null 检查
            //   → SystemUI crash-loop(每~7s, startDozing→dealWithFlipChange 触发, 早于 dream)。
            // 精准修: hook getCutoutPosition → CAMERA_CUTOUT_ON_NONE(AOD 不读 cutout, 不崩),
            //   不影响全局 Display.getCutout(TinyKeyguardPanel 依赖物理 cutout 不受影响)。
            // 2026-08-20 设备实测基准(flip1 属性4 原生): 外屏 cutout 运行时即空(非 null 空实例),
            //   getCutoutPosition 原生返回 NONE → 本 hook 是"源头真变 null 时"的第二道保险。
            installAodCutoutDefense(param.classLoader)
            hookFullAodEnable(param.classLoader)
            hookDreamService(param.classLoader)
        }
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()

    // ── MiuiFullAodManager.fullAodEnable() → false（2026-08-21, 方案 B）──
    //
    // 根因链(refMD AOD_Full_Chain, 2026-08-21 实锤 + 用户实测):
    //   full_screen_aod_on=1(默认"和锁屏一致") → MiuiFullAodManager.mAodFullScreenEnable
    //     → fullAodEnable()=isDeviceSupport&&mAodEnable&&mAodFullScreenEnable&&isFullAodSupport
    //     → DozeServiceHostInjector.mFullAodEnable → mScreenOffNeedFullAodAnim
    //     → MiuiDozeService tag → DozeHost.isFullAod()=true
    //     → prepareAodViewAndShow 移除插件时钟容器 → 外屏 AOD=systemui 锁屏时钟+黑
    //   用户实测: full_screen_aod_on=0 后外屏 AOD 变为 animate_clock_panel(内屏多样式) ✓
    //   本 hook 让模块自动处理(不依赖设置): fullAodEnable→false → needFullAod=false
    //   → 插件时钟容器保留 → 走 aod_category_name 多样式。
    //   位置: systemui 主 classloader(com.android.keyguard.fullaod.MiuiFullAodManager),
    //   注入即装(processClassLoader), 比 L2 的 DozeHost.isFullAod→false 更上游、无时序竞态。
    //   开关: 与 displayAod 共用(persist.flipunlock.display.aod)。
    private fun hookFullAodEnable(fallback: ClassLoader) {
        runCatching {
            val cl = processClassLoader(fallback)
            val cls = cl.loadClass("com.android.keyguard.fullaod.MiuiFullAodManager")
            val m = cls.getDeclaredMethod("fullAodEnable").apply { isAccessible = true }
            hook(m, replaceResult(false))
            log("AodHook: ✓ MiuiFullAodManager.fullAodEnable → false (外屏 AOD 走插件多样式)")
        }.onFailure { log("AodHook: fullAodEnable failed: ${it.message}") }
    }

    // ── 层 B: DisplayUtils.getCutoutPosition(Context) → CAMERA_CUTOUT_ON_NONE ──
    //
    // 崩溃链实锤(refMD §28.2):
    //   DisplayUtils.getCutoutPosition(DisplayUtils.java:18-29):
    //     DisplayCutout cutout = context.getDisplay().getCutout();   ← 无 null 检查
    //     if (!cutout.getBoundingRectLeft().isEmpty()) → LEFT
    //     if (!cutout.getBoundingRectRight().isEmpty()) → RIGHT
    //     return CAMERA_CUTOUT_ON_NONE
    //   调用点全包唯一 = DozeHost.dealWithFlipChange L796(DozeHost.java:780-844):
    //     L784 if (Utils.isFlipDevice()) → L789 isTinyScreen → L795 setFlipped(true)
    //     → L796 flipLinkageStyleController.updateClockOrientation(getCutoutPosition(mContext))
    //   触发时机 = DozeHost.startDozing L601(dealWithFlipChange(config, true)),
    //   早于 Layer 2(onDreamingStarted) → 层 B 必须在注入早期装好, 不能只靠 L2。
    //
    // classloader 隔离(§26 Note on AodHook Loading): com.miui.aod.* 在 MIUIAod.apk 独立
    //   plugin classloader。本版多候选: ① 进程 Application classLoader(processClassLoader)
    //   ② 回调 classLoader(框架) ③ ActivityThread.mAllClassLoaders 全量遍历。
    //   任一个能加载到 DisplayUtils 即 hook; 全失败则在 Layer 2 用 DozeMachine 的
    //   classloader 补装(installRuntimeHooks)。
    private fun installAodCutoutDefense(fallback: ClassLoader) {
        if (aodCutoutDefenseCl != null) return
        val candidates = buildList {
            add(processClassLoader(fallback))
            add(fallback)
            addAll(allClassLoaders())
        }.distinct()
        for (cl in candidates) {
            val ok = runCatching {
                val duCls = cl.loadClass("com.miui.aod.util.DisplayUtils")
                val dirCls = cl.loadClass("com.miui.aod.widget.Direction")
                val noneDir = dirCls.getField("CAMERA_CUTOUT_ON_NONE").get(null)
                val m = duCls.method("getCutoutPosition", android.content.Context::class.java)
                hook(m, replaceResult(noneDir))
                true
            }.getOrDefault(false)
            if (ok) {
                aodCutoutDefenseCl = cl
                log("AodHook: ✓ DisplayUtils.getCutoutPosition → CAMERA_CUTOUT_ON_NONE (AOD NPE 防御, cl=${cl})")
                // 同 classloader 顺带装 DozeLifecycleOwner.initState 防崩(若 plugin 已加载)
                hookDozeLifecycleOwnerInitState(cl)
                return
            }
        }
        log("AodHook: getCutoutPosition 防御未装上(无 classloader 可见 com.miui.aod) — 依赖 CutoutAlwaysHook #2 非 null cutout 兜底")
    }

    /** ActivityThread.mAllClassLoaders 全量遍历 —— 覆盖 plugin classloader 已创建的场景. */
    private fun allClassLoaders(): List<ClassLoader> {
        val result = mutableListOf<ClassLoader>()
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching
            val f = at.getDeclaredField("mAllClassLoaders").apply { isAccessible = true }
            when (val v = f.get(thread)) {
                is Array<*> -> v.forEach { if (it is ClassLoader) result.add(it) }
                is List<*> -> v.forEach { if (it is ClassLoader) result.add(it) }
                is Iterable<*> -> v.forEach { if (it is ClassLoader) result.add(it) }
            }
        }.onFailure { log("AodHook: allClassLoaders failed: ${it.message}") }
        return result
    }

    // ── DozeLifecycleOwner.initState 防崩(2026-08-21) ──
    //
    // 崩溃实锤(dropbox system_app_crash 8-19 起 14 次, 最新 8-21 21:27):
    //   java.lang.IllegalStateException: no event up from DESTROYED
    //     at LifecycleRegistry.forwardPass / sync / moveToState / setCurrentState
    //     at com.miui.aod.DozeLifecycleOwner.initState(DozeLifecycleOwner.java:40)
    //     at DozeFactory.assembleMachine → DozeService.create → MiuiDozeService.onCreate
    // 机制(FlipRes/aod 实锤):
    //   DozeLifecycleOwner 是 static 单例, mLifecycleRegistry(final) 跨 dream 周期复用;
    //   DozeMachine 进 FINISH → performTransitionOnComponents → Part.transitionTo(FINISH)
    //     → DozeLifecycleOwner: handleLifecycleEvent(ON_DESTROY) → registry=DESTROYED(终态)
    //   → 下次 dream create → assembleMachine → initState → setCurrentState(INITIALIZED)
    //     → LifecycleRegistry 拒绝从 DESTROYED forward → IllegalStateException → SystemUI 崩。
    // 触发: 模块 hook(#3 强制亮屏/L2 强制 DOZE_AOD)使 dream 在同一进程内结束后重建
    //   (原生 dream 结束→进程清理, 不复用 registry)。8-19 前无此崩溃, 模块引入。
    // 修复: hook initState, before 若 registry 状态==DESTROYED → 反射直写 mState=INITIALIZED
    //   (绕过 forwardPass 校验; observer 在 DESTROYED 时已 removeObserve, 状态一致)。
    // 时机: 层 B(注入时, plugin 已加载则装) + L2(onDreamingStarted, machineCl 必达, 覆盖
    //   第一次 dream 之后的所有 create)。
    private fun hookDozeLifecycleOwnerInitState(cl: ClassLoader) {
        if (!initStateHookedCls.add(cl)) return
        runCatching {
            val ownerCls = cl.loadClass("com.miui.aod.DozeLifecycleOwner")
            val registryCls = cl.loadClass("androidx.lifecycle.LifecycleRegistry")
            val stateCls = cl.loadClass("androidx.lifecycle.Lifecycle\$State")
            val destroyed = stateCls.getField("DESTROYED").get(null)
            val initialized = stateCls.getField("INITIALIZED").get(null)
            val m = ownerCls.getDeclaredMethod("initState").apply { isAccessible = true }
            hook(m, before { chain ->
                runCatching {
                    val owner = chain.thisObject
                    val regField = ownerCls.getDeclaredField("mLifecycleRegistry").apply { isAccessible = true }
                    val registry = regField.get(owner)
                    val mStateField = registryCls.getDeclaredField("mState").apply { isAccessible = true }
                    if (mStateField.get(registry) == destroyed) {
                        mStateField.set(registry, initialized)
                        log("AodHook: ✓ DozeLifecycleOwner registry DESTROYED→INITIALIZED (initState 防崩)")
                    }
                }.onFailure { log("AodHook: initState registry reset failed: ${it.message}") }
                chain.proceed()
            })
            log("AodHook: ✓ DozeLifecycleOwner.initState 防崩 hook (cl=${cl})")
        }.onFailure { log("AodHook: initState hook failed: ${it.message}") }
    }

    // ── #3/#4 framework DreamService (visible from SystemUI) ──
    private fun hookDreamService(classLoader: ClassLoader) {
        // #3 setDozeScreenState(int): 全状态强制 2(ON 亮屏) —— FlipOuterUnlock 最旧版方案
        // 旧版注释: DozeScreenState 有 6s mResetScreenTask 超时, INITIALIZED→DOZE_AOD 后调
        // setDozeScreenState(1); 阻塞所有 OFF 状态(0,1,3)并强制 2(ON); 4(AOD ON)也改 2,
        // 避免复位超时振荡。AOD 内容渲染在"亮屏 ON"状态 → 物理屏必亮(flip1 实测 4 方案不亮)。
        // 值: 0=FINISH 1=DOZE 2=ON/PULSING 3=DOZE_SUSPEND 4=DOZE_AOD
        runCatching {
            val method = android.service.dreams.DreamService::class.java
                .getDeclaredMethod("setDozeScreenState", Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                when (state) {
                    0, 1, 3, 4 -> {
                        log("AodHook: #3 setDozeScreenState($state) → 2 (ON 亮屏, 旧版方案)")
                        chain.proceed(arrayOf<Any?>(2))
                    }
                    else -> chain.proceed()   // 2 (ON) pass through
                }
            }
            log("AodHook: #3 DreamService.setDozeScreenState hooked [旧版: →2 亮屏]")
        }.onFailure { log("AodHook: #3 setDozeScreenState failed", it) }

        // #4 onDreamingStarted(): one-shot trigger for the runtime (Layer 2) hooks.
        runCatching {
            val method = android.service.dreams.DreamService::class.java
                .getDeclaredMethod("onDreamingStarted")
            method.isAccessible = true
            hook(method, after { chain, result ->
                if (!runtimeHooksInstalled) installRuntimeHooks(chain.thisObject)
                result
            })
            log("AodHook: #4 DreamService.onDreamingStarted hooked")
        }.onFailure { log("AodHook: #4 onDreamingStarted failed", it) }
    }

    // ── Layer 2: runtime hooks via the DozeMachine's own classloader ──
    private fun installRuntimeHooks(dreamService: Any?) {
        if (dreamService == null || runtimeHooksInstalled) return
        runtimeHooksInstalled = true
        runCatching {
            val machine = findObjectByClassName(dreamService, "com.miui.aod.doze.DozeMachine")
                ?: run { log("AodHook/L2: DozeMachine not found"); return }
            val machineCl = machine.javaClass.classLoader
                ?: run { log("AodHook/L2: DozeMachine classloader null"); return }
            log("AodHook/L2: found DozeMachine, cl=${machineCl.javaClass.simpleName}")

            // DozeLifecycleOwner.initState 防崩(2026-08-21): DozeService.create 每次调 initState,
            //   若上次 session 走到 FINISH(registry=DESTROYED) → setCurrentState(INITIALIZED) 抛
            //   IllegalStateException "no event up from DESTROYED" → SystemUI 崩(dropbox 8-19 起 14 次)。
            //   必须用真实 plugin classloader(machineCl) 装。
            hookDozeLifecycleOwnerInitState(machineCl)

            // 层 B 补装: plugin classloader 此刻一定可见 com.miui.aod.* —— 覆盖注入早期
            // 候选都找不到 DisplayUtils 的场景(此时 startDozing 已过, 但翻转/重连仍会
            // 再走 dealWithFlipChange, 且 CutoutZero 若致 null 后续 dream 周期仍需防御)。
            // 若 setupHooks 阶段已装到别的 classloader(非真实 AOD loader), 也在此补装
            // 真实 loader 那一份(review 2026-08-20 should-fix #1)。
            if (aodCutoutDefenseCl != machineCl) {
                runCatching {
                    val duCls = machineCl.loadClass("com.miui.aod.util.DisplayUtils")
                    val dirCls = machineCl.loadClass("com.miui.aod.widget.Direction")
                    val noneDir = dirCls.getField("CAMERA_CUTOUT_ON_NONE").get(null)
                    val m = duCls.method("getCutoutPosition", android.content.Context::class.java)
                    hook(m, replaceResult(noneDir))
                    aodCutoutDefenseCl = machineCl
                    log("AodHook/L2: ✓ getCutoutPosition → NONE (补装, machineCl=${machineCl})")
                }.onFailure { log("AodHook/L2: getCutoutPosition 补装失败: ${it.message}") }
            }

            val stateClass = machineCl.loadClass("com.miui.aod.doze.DozeMachine\$State")
            val values = stateClass.getMethod("values").invoke(null) as Array<*>
            val dozeAod = values.first { it.toString() == "DOZE_AOD" }

            // Force AOD immediately.
            runCatching { machine.callMethod("requestState", dozeAod) }
                .onFailure { log("AodHook/L2: initial requestState(DOZE_AOD) failed", it) }

            hookRequestState(machine, stateClass, dozeAod)
            hookDozeServiceSetDozeScreenState(dreamService)
            hookDozeHostIsFullAod(dreamService)
            hookFlipLinkageStyleController(machineCl)
        }.onFailure { log("AodHook/L2: installRuntimeHooks failed", it) }
    }

    // DozeMachine.requestState(): redirect DOZE/DOZE_SUSPEND/FINISH → DOZE_AOD.
    private fun hookRequestState(machine: Any, stateClass: Class<*>, dozeAod: Any?) {
        runCatching {
            val reqMethod = machine.javaClass.getDeclaredMethod("requestState", stateClass)
            reqMethod.isAccessible = true
            hook(reqMethod) { chain ->
                when (chain.args[0]?.toString()) {
                    "DOZE", "DOZE_SUSPEND", "FINISH" -> chain.proceed(arrayOf<Any?>(dozeAod))
                    else -> chain.proceed()
                }
            }
            log("AodHook/L2: DozeMachine.requestState → DOZE_AOD")
        }.onFailure { log("AodHook/L2: requestState hook failed", it) }
    }

    // DozeService.setDozeScreenState(int): 同 #3 —— 全状态强制 2(ON 亮屏, 旧版方案).
    private fun hookDozeServiceSetDozeScreenState(dreamService: Any) {
        val dozeService = findObjectByClassName(dreamService, "com.miui.aod.doze.DozeService") ?: return
        runCatching {
            val method = dozeService.javaClass.getDeclaredMethod(
                "setDozeScreenState", Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                val s = chain.args[0] as? Int ?: return@hook chain.proceed()
                when (s) {
                    0, 1, 3, 4 -> chain.proceed(arrayOf<Any?>(2))
                    else -> chain.proceed()
                }
            }
            log("AodHook/L2: DozeService.setDozeScreenState → 2 (旧版方案)")
        }.onFailure { log("AodHook/L2: DozeService.setDozeScreenState failed", it) }
    }

    // DozeHost.isFullAod() → false (prevent clock-container removal).
    private fun hookDozeHostIsFullAod(dreamService: Any) {
        val dozeHost = findObjectByClassName(dreamService, "com.miui.aod.DozeHost") ?: return
        runCatching {
            val method = dozeHost.javaClass.getDeclaredMethod("isFullAod")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("AodHook/L2: DozeHost.isFullAod → false")
        }.onFailure { log("AodHook/L2: DozeHost.isFullAod failed", it) }
    }

    // FlipLinkageStyleController: isFlipped()→false, isUsingFlip()→true (kill switch).
    private fun hookFlipLinkageStyleController(machineCl: ClassLoader) {
        runCatching {
            val ctrlClass = machineCl.loadClass("com.miui.aod.flip.FlipLinkageStyleController")
            // ensure the singleton exists before hooking its instance methods
            ctrlClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                ?: run { log("AodHook/L2: FlipLinkageStyleController.INSTANCE null"); return }
            runCatching {
                val m = ctrlClass.getDeclaredMethod("isFlipped").apply { isAccessible = true }
                // [2026-08-21 属性4版 v2] true→false: 用户需求 = 外屏 AOD 显示"内屏正常多样式息屏",
                //   而非 FlipLinkage 外屏样式(简单时钟/萌宠, flip 系列专属, 用户不要)。
                //   判定链(FlipRes/aod 实锤, refMD §30):
                //     isAodEnable = isFlipDevice && isFlipped → isUsingFlip(FlipLinkage 样式)
                //                  : isAodSettingsEnabled()          ← 内屏标准多样式路径
                //     isUsingLinkageStyle = isUsingFlip && isFlipped  ← 两者都 true 才走 FlipLinkage
                //   → hook isFlipped=false: isAodEnable 走内屏路径(isAodSettingsEnabled), 且
                //     isUsingLinkageStyle = true && false = false → 不走 FlipLinkage 萌宠/简单时钟。
                //   存活链不受影响: DozeMachine.resolveIntermediateState kill switch =
                //     isUsingFlip(true) || !isFlipped(false→true) → survive;
                //     dealWithFlipChange L806 isUsingFlip(true) → DOZE_AOD_PAUSING 分支(AOD 不灭)。
                //   注: dealWithFlipChange L795 setFlipped(true) 直写 static 字段, 读方法走本 hook → false。
                //   设置侧佐证: aod 设置页 "息屏样式" 默认 full_screen_aod_on=1(和锁屏样式一致,
                //   aod_resting_screen_same_to_lock), 设计上外屏 AOD 本就应走标准样式而非 FlipLinkage。
                hook(m, replaceResult(false))
                log("AodHook/L2: FlipLinkageStyleController.isFlipped → false (外屏 AOD 走内屏多样式息屏)")
            }.onFailure { log("AodHook/L2: isFlipped failed", it) }
            runCatching {
                val m = ctrlClass.getDeclaredMethod("isUsingFlip", android.content.Context::class.java)
                    .apply { isAccessible = true }
                hook(m, replaceResult(true))
                log("AodHook/L2: FlipLinkageStyleController.isUsingFlip → true")
            }.onFailure { log("AodHook/L2: isUsingFlip failed", it) }
        }.onFailure { log("AodHook/L2: FlipLinkageStyleController not found", it) }
    }

    // ── Object graph traversal (max depth 5, cycle-safe) ────────────────

    private fun findObjectByClassName(root: Any?, className: String): Any? =
        findRecursive(root, className, mutableSetOf(), 0)

    private fun findRecursive(obj: Any?, target: String, visited: MutableSet<Int>, depth: Int): Any? {
        if (obj == null || depth > 5) return null
        if (!visited.add(System.identityHashCode(obj))) return null
        if (obj.javaClass.name == target) return obj
        for (field in obj.javaClass.declaredFields) {
            runCatching {
                field.isAccessible = true
                val value = field.get(obj) ?: return@runCatching
                val fc = value.javaClass
                if (fc.isPrimitive || fc.name.startsWith("java.") ||
                    (fc.name.startsWith("android.") && !fc.name.contains("aod") && !fc.name.contains("doze"))
                ) return@runCatching
                findRecursive(value, target, visited, depth + 1)?.let { return it }
            }
        }
        return null
    }
}
