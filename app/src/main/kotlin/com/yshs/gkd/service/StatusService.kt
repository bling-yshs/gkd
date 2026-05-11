package com.yshs.gkd.service

import android.app.Service
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.yshs.gkd.META
import com.yshs.gkd.MainActivity
import com.yshs.gkd.a11y.useA11yServiceEnabledFlow
import com.yshs.gkd.app
import com.yshs.gkd.notif.abNotif
import com.yshs.gkd.permission.appOpsRestrictedFlow
import com.yshs.gkd.permission.foregroundServiceSpecialUseState
import com.yshs.gkd.permission.notificationState
import com.yshs.gkd.permission.requiredPermission
import com.yshs.gkd.permission.shizukuGrantedState
import com.yshs.gkd.permission.writeSecureSettingsState
import com.yshs.gkd.shizuku.uiAutomationFlow
import com.yshs.gkd.store.actionCountFlow
import com.yshs.gkd.store.storeFlow
import com.yshs.gkd.util.DefaultSimpleLifeImpl
import com.yshs.gkd.util.OnSimpleLife
import com.yshs.gkd.util.RuleSummary
import com.yshs.gkd.util.appInfoMapFlow
import com.yshs.gkd.util.getSubsStatus
import com.yshs.gkd.util.ruleSummaryFlow
import com.yshs.gkd.util.startForegroundServiceByClass
import com.yshs.gkd.util.stopServiceByClass

class StatusService : Service(), OnSimpleLife by DefaultSimpleLifeImpl() {
    override fun onBind(intent: Intent?) = null
    override fun onCreate() = onCreated()
    override fun onDestroy() = onDestroyed()

    val shizukuWarnFlow = combine(
        shizukuGrantedState.stateFlow,
        storeFlow.map { it.enableShizuku },
    ) { a, b ->
        !a && b
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val a11yServiceEnabledFlow = useA11yServiceEnabledFlow()

    fun statusTriple(): Triple<String, String, String?> {
        val abRunning = A11yService.isRunning.value
        val automationRunning = uiAutomationFlow.value != null
        val store = storeFlow.value
        val ruleSummary = ruleSummaryFlow.value
        val count = actionCountFlow.value
        val shizukuWarn = shizukuWarnFlow.value
        val title = if (store.useCustomNotifText) {
            store.customNotifTitle.replaceTemplate(ruleSummary, count)
        } else {
            META.appName
        }
        return if (appOpsRestrictedFlow.value) {
            Triple(title, "权限受限，请解除限制", "gkd://page/3")
        } else if (shizukuWarn) {
            Triple(title, "Shizuku 未连接，请授权或关闭优化", "gkd://page/1")
        } else if (!automationRunning && !abRunning) {
            if (currentAppUseA11y) {
                val text = if (a11yServiceEnabledFlow.value) {
                    "无障碍发生故障"
                } else if (writeSecureSettingsState.updateAndGet()) {
                    if (store.enableAutomator && store.enableBlockA11yAppList && a11yPartDisabledFlow.value) {
                        val name =
                            appInfoMapFlow.value[topAppIdFlow.value]?.name ?: topAppIdFlow.value
                        "局部关闭 · $name"
                    } else {
                        "无障碍已关闭"
                    }
                } else {
                    "无障碍未授权"
                }
                Triple(title, text, abNotif.uri)
            } else {
                val text =
                    if (store.enableAutomator && store.enableBlockA11yAppList && a11yPartDisabledFlow.value) {
                        val name =
                            appInfoMapFlow.value[topAppIdFlow.value]?.name ?: topAppIdFlow.value
                        "局部关闭 · $name"
                    } else {
                        "自动化已关闭"
                    }
                Triple(title, text, abNotif.uri)
            }
        } else if (!store.enableMatch) {
            Triple(title, "暂停规则匹配", "gkd://page?tab=1")
        } else if (store.useCustomNotifText) {
            Triple(
                title,
                store.customNotifText.replaceTemplate(ruleSummary, count),
                abNotif.uri
            )
        } else {
            Triple(title, getSubsStatus(ruleSummary, count), abNotif.uri)
        }
    }

    init {
        useAliveFlow(isRunning)
        useAliveToast(
            name = "常驻通知",
            delayMillis = if (app.justStarted) 1000 else 0,
        )
        onCreated {
            abNotif.notifyService()
            scope.launch {
                combine(
                    A11yService.isRunning,
                    uiAutomationFlow,
                    storeFlow,
                    ruleSummaryFlow,
                    shizukuWarnFlow,
                    a11yServiceEnabledFlow,
                    writeSecureSettingsState.stateFlow,
                    appOpsRestrictedFlow,
                    topAppIdFlow,
                    actionCountFlow.debounce(1000L),
                ) {
                    statusTriple()
                }
                    .stateIn(
                        scope,
                        SharingStarted.Eagerly,
                        Triple(abNotif.title, abNotif.text, abNotif.uri)
                    )
                    .collect {
                        abNotif.copy(
                            title = it.first,
                            text = it.second,
                            uri = it.third,
                        ).notifyService()
                    }
            }
        }
    }

    companion object {
        val isRunning = MutableStateFlow(false)
        val needRestart
            get() = storeFlow.value.enableStatusService
                    && !isRunning.value
                    && notificationState.updateAndGet()
                    && foregroundServiceSpecialUseState.updateAndGet()

        fun start() = startForegroundServiceByClass(StatusService::class)
        fun stop() = stopServiceByClass(StatusService::class)
        suspend fun requestStart(context: MainActivity) {
            requiredPermission(context, foregroundServiceSpecialUseState)
            requiredPermission(context, notificationState)
            start()
            storeFlow.update { it.copy(enableStatusService = true) }
        }

        private var lastAutoStart = 0L
        fun autoStart() {
            if (System.currentTimeMillis() - lastAutoStart < 1000) return
            // 重启自动打开通知栏状态服务
            // 需要已有服务或前台才能自主启动，否则报错 startForegroundService() not allowed due to mAllowStartForeground false
            if (needRestart) {
                start()
                lastAutoStart = System.currentTimeMillis()
            }
        }
    }
}

private fun String.replaceTemplate(ruleSummary: RuleSummary, count: Long): String {
    return replace($$"${i}", ruleSummary.globalGroups.size.toString())
        .replace($$"${k}", ruleSummary.appSize.toString())
        .replace($$"${u}", ruleSummary.appGroupSize.toString())
        .replace($$"${n}", count.toString())
}
