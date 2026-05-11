package com.yshs.gkd

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.URLUtil
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yshs.gkd.a11y.useA11yServiceEnabledFlow
import com.yshs.gkd.a11y.useEnabledA11yServicesFlow
import com.yshs.gkd.data.CrashData
import com.yshs.gkd.data.RawSubscription
import com.yshs.gkd.data.SubsItem
import com.yshs.gkd.db.DbSet
import com.yshs.gkd.permission.AuthReason
import com.yshs.gkd.permission.shizukuGrantedState
import com.yshs.gkd.service.A11yService
import com.yshs.gkd.shizuku.shizukuContextFlow
import com.yshs.gkd.shizuku.uiAutomationFlow
import com.yshs.gkd.shizuku.updateBinderMutex
import com.yshs.gkd.store.createTextFlow
import com.yshs.gkd.store.storeFlow
import com.yshs.gkd.ui.AdvancedPageRoute
import com.yshs.gkd.ui.AppOpsAllowRoute
import com.yshs.gkd.ui.CrashReportRoute
import com.yshs.gkd.ui.SnapshotPageRoute
import com.yshs.gkd.ui.WebViewRoute
import com.yshs.gkd.ui.component.AlertDialogOptions
import com.yshs.gkd.ui.component.InputSubsLinkOption
import com.yshs.gkd.ui.component.RuleGroupState
import com.yshs.gkd.ui.component.UploadOptions
import com.yshs.gkd.ui.home.BottomNavItem
import com.yshs.gkd.ui.home.HomeRoute
import com.yshs.gkd.ui.share.BaseViewModel
import com.yshs.gkd.util.AutomatorModeOption
import com.yshs.gkd.util.BackupUtils
import com.yshs.gkd.util.DefaultSimpleLifeImpl
import com.yshs.gkd.util.LOCAL_SUBS_ID
import com.yshs.gkd.util.LogUtils
import com.yshs.gkd.util.OnSimpleLife
import com.yshs.gkd.util.ThrottleTimer
import com.yshs.gkd.util.UpdateStatus
import com.yshs.gkd.util.appIconMapFlow
import com.yshs.gkd.util.clearCache
import com.yshs.gkd.util.client
import com.yshs.gkd.util.crashFolder
import com.yshs.gkd.util.crashTempFolder
import com.yshs.gkd.util.findOption
import com.yshs.gkd.util.json
import com.yshs.gkd.util.launchTry
import com.yshs.gkd.util.openUri
import com.yshs.gkd.util.openWeChatScaner
import com.yshs.gkd.util.runMainPost
import com.yshs.gkd.util.stopCoroutine
import com.yshs.gkd.util.subsFolder
import com.yshs.gkd.util.subsItemsFlow
import com.yshs.gkd.util.toast
import com.yshs.gkd.util.updateSubsMutex
import com.yshs.gkd.util.updateSubscription
import li.songe.loc.Loc
import rikka.shizuku.Shizuku
import java.nio.file.Files
import kotlin.reflect.jvm.jvmName
import kotlin.time.Duration.Companion.days

class MainViewModel : BaseViewModel(), OnSimpleLife by DefaultSimpleLifeImpl() {
    companion object {
        private var _instance: MainViewModel? = null
        val instance get() = _instance!!
        private var tempTermsAccepted = false
    }

    init {
        LogUtils.d("MainViewModel:init")
        _instance = this
        addCloseable {
            LogUtils.d("MainViewModel:close")
            if (_instance == this) { // 可能同时存在 2 个 MainViewModel 实例
                _instance = null
            }
        }
    }

    override val scope get() = viewModelScope

    val backStack: NavBackStack<NavKey> = NavBackStack(HomeRoute)
    val topRoute get() = backStack.last()

    private val backThrottleTimer = ThrottleTimer()

    fun popPage(@Loc loc: String = "") = runMainPost {
        if (backThrottleTimer.expired() && backStack.size > 1) {
            val old = backStack.last()
            backStack.removeAt(backStack.lastIndex)
            LogUtils.d("popPage", "$old -> ${backStack.last()}", loc = loc)
        }
    }

    fun navigatePage(
        navKey: NavKey,
        replaced: Boolean = false,
        @Loc loc: String = "",
    ) = runMainPost {
        if (navKey != backStack.last()) {
            val old = backStack.last()
            if (replaced) {
                backStack[backStack.lastIndex] = navKey
            } else {
                backStack.add(navKey)
            }
            LogUtils.d("navigatePage", "$old -> ${backStack.last()}", loc = loc)
        }
    }

    fun navigateWebPage(url: String) = navigatePage(WebViewRoute(url))

    val dialogFlow = MutableStateFlow<AlertDialogOptions?>(null)
    val authReasonFlow = MutableStateFlow<AuthReason?>(null)

    val updateStatus = if (META.updateEnabled) UpdateStatus(viewModelScope) else null

    val shizukuErrorFlow = MutableStateFlow<Throwable?>(null)

    val uploadOptions = UploadOptions(this)

    val showEditCookieDlgFlow = MutableStateFlow(false)

    val inputSubsLinkOption = InputSubsLinkOption()

    val sheetSubsIdFlow = MutableStateFlow<Long?>(null)

    val appOrderListFlow = DbSet.actionLogDao.queryLatestUniqueAppIds().stateInit(emptyList())
    val appVisitOrderMapFlow = DbSet.appVisitLogDao.query().map {
        it.mapIndexed { i, appId -> appId to i }.toMap()
    }.debounce(500).stateInit(emptyMap())

    fun addOrModifySubs(
        url: String,
        oldItem: SubsItem? = null,
    ) = viewModelScope.launchTry(Dispatchers.IO) {
        if (updateSubsMutex.mutex.isLocked) return@launchTry
        updateSubsMutex.withStateLock {
            val subItems = subsItemsFlow.value
            val text = try {
                client.get(url).bodyAsText()
            } catch (e: Exception) {
                e.printStackTrace()
                LogUtils.d(e)
                toast("下载订阅文件失败\n${e.message}".trimEnd())
                return@launchTry
            }
            val newSubsRaw = try {
                RawSubscription.parse(text)
            } catch (e: Exception) {
                e.printStackTrace()
                LogUtils.d(e)
                toast("解析订阅文件失败\n${e.message}".trimEnd())
                return@launchTry
            }
            if (oldItem == null) {
                if (subItems.any { it.id == newSubsRaw.id }) {
                    toast("订阅已存在")
                    return@launchTry
                }
            } else {
                if (oldItem.id != newSubsRaw.id) {
                    toast("订阅id不对应")
                    return@launchTry
                }
            }
            if (newSubsRaw.id < 0) {
                toast("订阅id不可为${newSubsRaw.id}\n负数id为内部使用")
                return@launchTry
            }
            val newItem = oldItem?.copy(updateUrl = url) ?: SubsItem(
                id = newSubsRaw.id,
                updateUrl = url,
                order = if (subItems.isEmpty()) 1 else (subItems.maxBy { it.order }.order + 1)
            )
            updateSubscription(newSubsRaw)
            if (oldItem == null) {
                DbSet.subsItemDao.insert(newItem)
                toast("成功添加订阅")
            } else {
                DbSet.subsItemDao.update(newItem)
                toast("成功修改订阅")
            }
        }
    }

    val ruleGroupState = RuleGroupState(this)

    val textFlow = MutableStateFlow<String?>(null)
    fun openUrl(url: String) {
        if (URLUtil.isNetworkUrl(url)) {
            textFlow.value = url
        } else {
            openUri(url)
        }
    }

    val tabFlow = MutableStateFlow(BottomNavItem.Control.key)
    val resetPageScrollEvent = MutableSharedFlow<BottomNavItem>()
    private var lastClickTabTime = 0L
    fun handleClickTab(navItem: BottomNavItem) {
        val t = System.currentTimeMillis()
        // double click
        if (navItem.key == tabFlow.value && t - lastClickTabTime < 500) {
            viewModelScope.launch { resetPageScrollEvent.emit(navItem) }
        }
        tabFlow.value = navItem.key
        lastClickTabTime = t
    }

    fun handleGkdUri(uri: Uri) {
        val notFoundToast = { toast("未知URI\n${uri}") }
        when (uri.host) {
            "page" -> when (uri.path) {
                "" -> {
                    val tab = uri.getQueryParameter("tab")?.toIntOrNull()
                    if (tab != null && BottomNavItem.allSubObjects.any { it.key == tab }) {
                        tabFlow.value = tab
                    }
                }

                "/1" -> navigatePage(AdvancedPageRoute)
                "/2" -> navigatePage(SnapshotPageRoute)
                "/3" -> navigatePage(AppOpsAllowRoute)
                else -> notFoundToast()
            }

            "invoke" -> when (uri.path) {
                "/1" -> openWeChatScaner()
                else -> notFoundToast()
            }

            else -> notFoundToast()
        }
    }

    fun handleIntent(intent: Intent) = viewModelScope.launchTry {
        LogUtils.d(intent)
        val uri = intent.data?.normalizeScheme()
        val source = intent.getStringExtra(activityNavSourceName)
        if (uri?.scheme == "gkd") {
            handleGkdUri(uri)
        } else if (source == OpenFileActivity::class.jvmName && uri != null) {
            withContext(Dispatchers.IO) { BackupUtils.importBackUpData(uri) }
        }
    }

    val termsAcceptedFlow by lazy {
        if (tempTermsAccepted) {
            MutableStateFlow(true)
        } else {
            createTextFlow(
                key = "terms_accepted",
                decode = { it == "true" },
                encode = {
                    tempTermsAccepted = it
                    it.toString()
                },
                scope = viewModelScope,
            ).apply {
                tempTermsAccepted = value
            }
        }
    }

    val githubCookieFlow by lazy {
        createTextFlow(
            key = "github_cookie",
            decode = { it ?: "" },
            encode = { it },
            private = true,
            scope = viewModelScope,
        )
    }

    fun switchEnableShizuku(value: Boolean) {
        if (updateBinderMutex.mutex.isLocked) {
            toast("正在连接中，请稍后")
            return
        }
        storeFlow.update { s -> s.copy(enableShizuku = value) }
    }

    fun requestShizuku() {
        if (shizukuContextFlow.value.ok) return
        if (updateBinderMutex.mutex.isLocked) {
            toast("正在连接中，请稍后")
            return
        }
        try {
            Shizuku.requestPermission(Activity.RESULT_OK)
        } catch (e: Throwable) {
            shizukuErrorFlow.value = e
        }
    }

    suspend fun guardShizukuContext() {
        if (shizukuContextFlow.value.ok) return
        if (!storeFlow.value.enableShizuku) {
            storeFlow.update { it.copy(enableShizuku = true) }
        }
        if (!shizukuGrantedState.updateAndGet()) {
            requestShizuku()
            stopCoroutine()
        }
        if (shizukuContextFlow.value.ok) return
        stopCoroutine()
    }

    private val a11yServicesFlow = useEnabledA11yServicesFlow()
    val a11yServiceEnabledFlow = useA11yServiceEnabledFlow(a11yServicesFlow)

    val automatorModeFlow = storeFlow.mapNew {
        AutomatorModeOption.objects.findOption(it.automatorMode)
    }

    fun updateAutomatorMode(option: AutomatorModeOption) {
        if (automatorModeFlow.value == option) return
        storeFlow.update { it.copy(automatorMode = option.value, enableAutomator = false) }
        A11yService.instance?.shutdown()
        uiAutomationFlow.value?.shutdown()
    }

    val showShareLogDlgFlow = MutableStateFlow(false)

    var tempCrashDataList = emptyList<CrashData>()

    init {
        // preload
        appIconMapFlow.value
        viewModelScope.launchTry(Dispatchers.IO) {
            val subsItems = DbSet.subsItemDao.queryAll()
            if (!subsItems.any { s -> s.id == LOCAL_SUBS_ID }) {
                if (!subsFolder.resolve("${LOCAL_SUBS_ID}.json").exists()) {
                    updateSubscription(
                        RawSubscription(
                            id = LOCAL_SUBS_ID,
                            name = "本地订阅",
                            version = 0
                        )
                    )
                }
                DbSet.subsItemDao.insert(
                    SubsItem(
                        id = LOCAL_SUBS_ID,
                        order = subsItems.minByOrNull { it.order }?.order ?: 0,
                    )
                )
            }
        }

        viewModelScope.launchTry(Dispatchers.IO) {
            // 每次进入删除缓存
            clearCache()
        }

        if (termsAcceptedFlow.value && updateStatus?.canRecheck == true) {
            updateStatus.checkUpdate()
        }

        viewModelScope.launch(Dispatchers.IO) {
            // preload
            githubCookieFlow.value
        }
        viewModelScope.launchTry(Dispatchers.IO) {
            val list = (crashTempFolder.listFiles() ?: emptyArray()).mapNotNull {
                try {
                    json.decodeFromString<CrashData>(it.readText())
                } catch (e: Exception) {
                    LogUtils.d("解析崩溃日志失败: ${it.name}", e)
                    null
                }
            }.sortedBy { -it.mtime }
            crashTempFolder.deleteRecursively()
            val t = System.currentTimeMillis()
            crashFolder.listFiles()?.filter {
                val name = it.name
                !list.any { f -> name == f.filename }
            }?.forEach {
                val mtime = Files.getLastModifiedTime(it.toPath()).toMillis()
                if (t - mtime > 30.days.inWholeMilliseconds) {
                    it.delete()
                }
            }
            tempCrashDataList = list
            if (list.isNotEmpty()) {
                navigatePage(CrashReportRoute)
            }
        }

        // for OnSimpleLife
        onCreated()
        addCloseable { onDestroyed() }
    }
}
