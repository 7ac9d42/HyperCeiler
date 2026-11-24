/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar.mobile

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresPermission
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreAndroidVersion
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion
import com.sevtinge.hyperceiler.libhook.utils.hookapi.StateFlowHelper.canAssignReadonlyStateFlowTo
import com.sevtinge.hyperceiler.libhook.utils.hookapi.StateFlowHelper.newReadonlyStateFlow
import com.sevtinge.hyperceiler.libhook.utils.hookapi.StateFlowHelper.setStateFlowValue
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileClass.miuiCellularIconVM
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileClass.miuiMobileIconBinder
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.card1
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.card2
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.hideIndicator
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.hideRoaming
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.isEnableDouble
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobilePrefs.signalShowMode
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileViewHelper
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileViewHelper.isMobileDataConnected
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.MobileViewHelper.isWifiConnected
import io.github.lingqiqi5211.ezhooktool.core.callMethodAs
import io.github.lingqiqi5211.ezhooktool.core.findField
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getAdditionalInstanceFieldAs
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldAs
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.hookAllConstructors
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.setAdditionalInstanceField
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.setObjectField
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

class MobilePublicHookV : BaseHook() {
    private companion object {
        const val STATE_CONTEXT = "MobilePublicHookV.context"
        const val STATE_SUB_IDS = "MobilePublicHookV.subIds"
        const val STATE_FLOW_PREFIX = "MobilePublicHookV.flow."
    }

    private val visibilityFlows = ConcurrentHashMap<Int, Any>()

    @Volatile
    private var broadcastRegistered = false

    @Volatile
    private var pairCtor: java.lang.reflect.Constructor<*>? = null

    @Volatile
    private var os4RoamingGetterOwner: Class<*>? = null

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_PHONE_STATE])
    override fun init() {
        restoreVisibilityFlowsAfterHotReload()

        if (isMoreAndroidVersion(37)) {
            if (hideRoaming) hookOs4RoamingGetters()
            hookOs4Visibility()
        } else {
            hookLegacyVisibility()
        }
    }

    private fun hookOs4RoamingGetters() {
        val handles = ArrayList<XposedInterface.HookHandle>(2)
        runCatching {
            val vm = loadClass("com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiMobileIconVMImpl")
            val small = vm.getDeclaredMethod("getSmallRoamVisible")
            val mobile = vm.getDeclaredMethod("getMobileRoamVisible")
            val flowType = small.returnType
            check(flowType.name == "kotlinx.coroutines.flow.Flow" && mobile.returnType == flowType &&
                !Modifier.isStatic(small.modifiers) && !Modifier.isStatic(mobile.modifiers))
            check(canAssignReadonlyStateFlowTo(flowType))
            val hiddenFlow = newReadonlyStateFlow(false)
            check(flowType.isInstance(hiddenFlow))
            val hook = object : IMethodHook {
                override fun before(param: HookParam) {
                    if (os4RoamingGetterOwner != null) param.result = hiddenFlow
                }
            }
            handles += checkNotNull(hookMethod(small, hook))
            handles += checkNotNull(hookMethod(mobile, hook))
            registerHotReloadCleanup { os4RoamingGetterOwner = null }
            os4RoamingGetterOwner = vm
        }.onFailure { error ->
            for (handle in handles.asReversed()) {
                runCatching { handle.unhook() }
                    .onFailure { rollback -> if (error !== rollback) error.addSuppressed(rollback) }
            }
            debugCallbackError("OS4 roaming getters", error)
        }
    }

    /**
     * OS4 (Android 17): MiuiCellularIconVM 改为无参构造 + 字段赋值，
     * 构造回调时字段尚未赋值，无法在构造时替换。
     * binder 直接消费 MiuiMobileIconVMImpl.isVisible（bind 的 args[2]），
     * 在 bind 回调中直接替换该 StateFlow 即可生效。
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_PHONE_STATE])
    private fun hookOs4Visibility() {
        miuiMobileIconBinder.findMethod { name("bind") }
            .createAfterHook { param ->
                val vmImpl = param.args[2] ?: return@createAfterHook
                // bind 会对同一 VM 多次调用，已替换过则跳过，避免旧视图收集的 flow 失效
                if (vmImpl.getAdditionalInstanceFieldAs<Boolean?>("visibilityReplaced") == true) {
                    return@createAfterHook
                }
                val subId = runCatching {
                    vmImpl.getObjectFieldAs<Any>("iconInteractor")
                        .getObjectFieldAs<Int>("subId")
                }.getOrNull() ?: runCatching {
                    param.args[1]?.getObjectFieldAs<Any>("commonImpl")
                        ?.callMethodAs<Int>("getSubscriptionId")
                }.getOrNull() ?: return@createAfterHook
                vmImpl.setAdditionalInstanceField("visibilityReplaced", true)
                applyVisibility(vmImpl, subId)
            }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_PHONE_STATE])
    private fun hookLegacyVisibility() {
        miuiCellularIconVM.hookAllConstructors {
            after { param ->
                val cellularIcon = param.thisObject
                val mobileIconInteractor = param.args[2] ?: return@after
                val subId = mobileIconInteractor.getObjectFieldAs<Int>("subId")
                applyVisibility(cellularIcon, subId)
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_PHONE_STATE])
    private fun applyVisibility(cellularIcon: Any, subId: Int) {
        when {
            // 信号显示逻辑
            signalShowMode >= 1 -> {
                val isVisible = createVisibilityFlow()
                cellularIcon.setObjectField("isVisible", isVisible)
                trackVisibilityFlow(subId, isVisible)
                refreshVisibility(subId, isVisible)
                registerReceiver(EzXposed.appContext)
            }
            // 双排信号（signalShowMode == 0 且未隐藏卡）
            isEnableDouble && !(card1 || card2) -> {
                val isVisible = createVisibilityFlow()
                cellularIcon.setObjectField("isVisible", isVisible)
                trackVisibilityFlow(subId, isVisible)
                val slotIndex = SubscriptionManager.getSlotIndex(subId)
                val shouldShow = !MobileViewHelper.isAirplaneModeOn() &&
                    (MobileViewHelper.isSingleSimMode() || slotIndex == 0)
                updateVisibility(isVisible, shouldShow)
                registerReceiver(EzXposed.appContext)
            }
            // 隐藏指定卡
            else -> {
                val slotIndex = SubscriptionManager.getSlotIndex(subId)
                if ((card1 && slotIndex == 0) || (card2 && slotIndex == 1)) {
                    cellularIcon.setObjectField("isVisible", createVisibilityFlow())
                }
            }
        }

        if (hideIndicator) {
            runCatching {
                cellularIcon.setObjectField("inOutVisible", newReadonlyStateFlow(false))
            }
        }
        if (hideRoaming && os4RoamingGetterOwner?.isInstance(cellularIcon) != true) {
            forceRoamHidden(cellularIcon, "smallRoamVisible")
            forceRoamHidden(cellularIcon, "mobileRoamVisible")
        }
    }

    // ==================== Flow 工具 ====================
    private fun trackVisibilityFlow(subId: Int, flow: Any) {
        visibilityFlows[subId] = flow
        // ReadonlyStateFlow 由 SystemUI 的 kotlinx.coroutines classloader 创建；只有它不是
        // 模块对象时才跨 generation 保存。这样既能让现有 ViewModel 继续接收更新，也不会把
        // 模块 classloader / lambda 带进 SavedInstanceState。
        if (flow.javaClass.classLoader === javaClass.classLoader) {
            return
        }
        BaseHook.putHotReloadRuntimeState("$STATE_FLOW_PREFIX$subId", flow)
        BaseHook.putHotReloadRuntimeState(
            STATE_SUB_IDS,
            visibilityFlows.keys.sorted().joinToString(",")
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_PHONE_STATE])
    private fun restoreVisibilityFlowsAfterHotReload() {
        if (signalShowMode < 1 && !(isEnableDouble && !(card1 || card2))) {
            return
        }
        val savedIds = BaseHook.getHotReloadRuntimeState(STATE_SUB_IDS, String::class.java)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            .orEmpty()
        savedIds.forEach { subId ->
            val flow = BaseHook.getHotReloadRuntimeState("$STATE_FLOW_PREFIX$subId", Any::class.java)
                ?: return@forEach
            if (flow.javaClass.classLoader !== javaClass.classLoader) {
                visibilityFlows[subId] = flow
            }
        }
        if (visibilityFlows.isEmpty()) {
            return
        }
        BaseHook.getHotReloadRuntimeState(STATE_CONTEXT, Context::class.java)?.let(::registerReceiver)
        refreshAllVisibility()
    }

    private fun needPairFlow(): Boolean {
        return isMoreAndroidVersion(36) || isMoreHyperOSVersion(3f)
    }

    private fun newPair(value: Boolean): Any {
        val ctor = pairCtor ?: loadClass("kotlin.Pair")
            .getConstructor(Object::class.java, Object::class.java)
            .also { pairCtor = it }
        return ctor.newInstance(value, value)
    }

    private fun createVisibilityFlow(): Any {
        return if (needPairFlow()) {
            newReadonlyStateFlow(newPair(false))
        } else {
            newReadonlyStateFlow(false)
        }
    }

    private fun updateVisibility(isVisible: Any, shouldShow: Boolean) {
        if (needPairFlow()) {
            setStateFlowValue(isVisible, newPair(shouldShow))
        } else {
            setStateFlowValue(isVisible, shouldShow)
        }
    }


    /**
     * 隐藏漫游图标。
     *
     * 旧版字段可以接收 ReadonlyStateFlow；声明为具体 combine 类型的字段必须保留
     * 原 Flow 对象，只能替换其 transform。其他 Flow 不一定有该 transform。
     */
    private fun forceRoamHidden(cellularIcon: Any, fieldName: String) {
        val field = runCatching {
            cellularIcon.javaClass.findField { name(fieldName) }
        }.getOrNull() ?: return

        if (runCatching {
                if (!canAssignReadonlyStateFlowTo(field.type)) return@runCatching false
                field.set(cellularIcon, newReadonlyStateFlow(false))
                true
            }.getOrDefault(false)) return

        runCatching {
            val flowObj = field.get(cellularIcon) ?: return
            val fakeTransform = createAlwaysFalseTransform(flowObj) ?: return
            flowObj.setObjectField($$"$transform$inlined$1", fakeTransform)
        }
    }

    /**
     * 根据 combine 匿名 Flow 对象创建一个同元数、恒返 false 的 Function 代理。
     * 通过读取 \$transform\$inlined\$1 原始实现的所有接口来推断 Function 元数，
     * 避免硬编码 \$r8\$classId 与 Function4/5/6 的映射关系（不同版本映射可能不同）。
     */
    private fun createAlwaysFalseTransform(flowObj: Any): Any? {
        val originalTransform = runCatching {
            flowObj.getObjectFieldAs<Any>($$"$transform$inlined$1")
        }.getOrNull() ?: return null

        val interfaces = originalTransform.javaClass.interfaces
            .filter { it.name.startsWith("kotlin.jvm.functions.Function") }
            .toTypedArray()
        if (interfaces.isEmpty()) return null

        val cl = originalTransform.javaClass.classLoader ?: javaClass.classLoader
        return java.lang.reflect.Proxy.newProxyInstance(cl, interfaces) { _, method, _ ->
            when (method.name) {
                "invoke" -> false
                "toString" -> "AlwaysFalseTransform"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        }
    }

    // ==================== 统一可见性刷新 ====================
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_PHONE_STATE])
    private fun refreshVisibility(subId: Int, isVisible: Any) {
        val isAirplane = MobileViewHelper.isAirplaneModeOn()
        val mode = signalShowMode
        val shouldShow = !isAirplane && when (mode) {
            1 -> !isWifiConnected()
            2, 3 -> subId == SubscriptionManager.getDefaultDataSubscriptionId() &&
                (mode == 3 || isMobileDataConnected())
            else -> MobileViewHelper.isSingleSimMode() || SubscriptionManager.getSlotIndex(subId) == 0
        }
        updateVisibility(isVisible, shouldShow)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_PHONE_STATE])
    private fun refreshAllVisibility() {
        val isAirplane = MobileViewHelper.isAirplaneModeOn()
        val mode = signalShowMode
        val isSingleSim = mode !in 1..3 && MobileViewHelper.isSingleSimMode()
        val defaultDataSubId = if (mode == 2 || mode == 3) {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } else {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val wifiConnected = mode == 1 && MobileViewHelper.isWifiConnected()
        val mobileDataConnected = mode == 2 && MobileViewHelper.isMobileDataConnected()

        visibilityFlows.forEach { (subId, isVisible) ->
            val shouldShow = when (mode) {
                1 -> !isAirplane && !wifiConnected
                2 -> !isAirplane && subId == defaultDataSubId && mobileDataConnected
                3 -> !isAirplane && subId == defaultDataSubId
                else -> !isAirplane && (isSingleSim || SubscriptionManager.getSlotIndex(subId) == 0)
            }
            updateVisibility(isVisible, shouldShow)
        }
    }

    // ==================== 广播监听 ====================
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    @Synchronized
    private fun registerReceiver(context: Context) {
        if (broadcastRegistered) return

        val filter = IntentFilter().apply {
            addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")
            addAction("android.intent.action.SIM_STATE_CHANGED")
            addAction("android.intent.action.AIRPLANE_MODE")
        }
        val receiver = object : BroadcastReceiver() {
            @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_PHONE_STATE])
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refreshAllVisibility()
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        BaseHook.registerReceiverHotReloadCleanup(context, receiver)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_PHONE_STATE])
            override fun onLost(network: Network) = refreshAllVisibility()

            // 默认网络首次可用时，同一次通知还会分发 onCapabilitiesChanged。
            @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_PHONE_STATE])
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                refreshAllVisibility()
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        BaseHook.registerNetworkCallbackHotReloadCleanup(cm, callback)
        broadcastRegistered = true
        BaseHook.putHotReloadRuntimeState(STATE_CONTEXT, context)
    }
}
