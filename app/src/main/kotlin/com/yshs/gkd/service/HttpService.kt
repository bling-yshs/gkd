package com.yshs.gkd.service

import android.app.Service
import android.content.Intent
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import com.yshs.gkd.a11y.A11yRuleEngine
import com.yshs.gkd.appScope
import com.yshs.gkd.data.AppInfo
import com.yshs.gkd.data.DeviceInfo
import com.yshs.gkd.data.GkdAction
import com.yshs.gkd.data.RawSubscription
import com.yshs.gkd.data.RpcError
import com.yshs.gkd.data.SubsItem
import com.yshs.gkd.data.selfAppInfo
import com.yshs.gkd.db.DbSet
import com.yshs.gkd.notif.StopServiceReceiver
import com.yshs.gkd.notif.httpNotif
import com.yshs.gkd.store.storeFlow
import com.yshs.gkd.util.DefaultSimpleLifeImpl
import com.yshs.gkd.util.LOCAL_HTTP_SUBS_ID
import com.yshs.gkd.util.LogUtils
import com.yshs.gkd.util.OnSimpleLife
import com.yshs.gkd.util.SERVER_SCRIPT_URL
import com.yshs.gkd.util.SnapshotExt
import com.yshs.gkd.util.SnapshotExt.getMinSnapshot
import com.yshs.gkd.util.deleteSubscription
import com.yshs.gkd.util.getIpAddressInLocalNetwork
import com.yshs.gkd.util.isPortAvailable
import com.yshs.gkd.util.keepNullJson
import com.yshs.gkd.util.launchTry
import com.yshs.gkd.util.mapState
import com.yshs.gkd.util.startForegroundServiceByClass
import com.yshs.gkd.util.stopServiceByClass
import com.yshs.gkd.util.subsItemsFlow
import com.yshs.gkd.util.toast
import com.yshs.gkd.util.updateSubscription


class HttpService : Service(), OnSimpleLife by DefaultSimpleLifeImpl() {
    override fun onBind(intent: Intent?) = null
    override fun onCreate() = onCreated()
    override fun onDestroy() = onDestroyed()

    val httpServerPortFlow = storeFlow.mapState(scope) { s -> s.httpServerPort }

    init {
        useLogLifecycle()
        useAliveFlow(isRunning)
        useAliveToast("HTTP服务")
        StopServiceReceiver.autoRegister()
        onCreated {
            scope.launchTry(Dispatchers.IO) {
                httpServerPortFlow.collect {
                    localNetworkIpsFlow.value = getIpAddressInLocalNetwork()
                }
            }
        }
        onDestroyed {
            if (storeFlow.value.autoClearMemorySubs) {
                deleteSubscription(LOCAL_HTTP_SUBS_ID)
            }
            httpServerFlow.value = null
        }
        onCreated {
            httpNotif.notifyService()
            scope.launchTry(Dispatchers.IO) {
                httpServerPortFlow.collect { port ->
                    val isReboot = httpServerFlow.value != null
                    httpServerFlow.apply {
                        value?.stop()
                        value = null
                    }
                    if (!isPortAvailable(port)) {
                        toast("端口 $port 被占用，请更换后重试")
                        stopSelf()
                        return@collect
                    }
                    httpServerFlow.value = try {
                        scope.createServer(port).apply { start() }
                    } catch (e: Exception) {
                        toast("HTTP服务启动失败:${e.stackTraceToString()}")
                        LogUtils.d("HTTP服务启动失败", e)
                        null
                    }
                    if (httpServerFlow.value == null) {
                        stopSelf()
                    } else if (isReboot) {
                        toast("HTTP服务重启成功")
                    }
                }
            }
        }
    }

    companion object {
        val httpServerFlow = MutableStateFlow<ServerType?>(null)
        val isRunning = MutableStateFlow(false)
        val localNetworkIpsFlow = MutableStateFlow(emptyList<String>())
        fun stop() = stopServiceByClass(HttpService::class)
        fun start() = startForegroundServiceByClass(HttpService::class)
    }
}

typealias ServerType = EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>


@Serializable
data class RpcOk(
    val message: String? = null,
)

@Serializable
data class ReqId(
    val id: Long,
)

@Serializable
data class ServerInfo(
    val device: DeviceInfo = DeviceInfo(),
    val gkdAppInfo: AppInfo = selfAppInfo
)

fun clearHttpSubs() {
    // 如果 app 被直接在任务列表划掉, HTTP订阅会没有清除, 所以在后续的第一次启动时清除
    if (HttpService.isRunning.value) return
    appScope.launchTry {
        delay(1000)
        if (storeFlow.value.autoClearMemorySubs) {
            deleteSubscription(LOCAL_HTTP_SUBS_ID)
        }
    }
}

private val httpSubsItem = SubsItem(
    id = LOCAL_HTTP_SUBS_ID,
    order = -1,
    enableUpdate = false,
)

private fun CoroutineScope.createServer(port: Int) = embeddedServer(CIO, port) {
    install(getKtorCorsPlugin())
    install(getKtorErrorPlugin())
    install(ContentNegotiation) { json(keepNullJson) }
    routing {
        get("/") { call.respondText(ContentType.Text.Html) { "<script type='module' src='$SERVER_SCRIPT_URL'></script>" } }
        route("/api") {
            post("/getServerInfo") { call.respond(ServerInfo()) }
            post("/getSnapshot") {
                val data = call.receive<ReqId>()
                val fp = SnapshotExt.snapshotFile(data.id)
                if (!fp.exists()) {
                    throw RpcError("对应快照不存在")
                }
                call.respondFile(fp)
            }
            post("/getScreenshot") {
                val data = call.receive<ReqId>()
                val fp = SnapshotExt.screenshotFile(data.id)
                if (!fp.exists()) {
                    throw RpcError("对应截图不存在")
                }
                call.respondFile(fp)
            }
            post("/captureSnapshot") {
                call.respond(SnapshotExt.captureSnapshot())
            }
            post("/getSnapshots") {
                val list = DbSet.snapshotDao.query().first().mapNotNull {
                    try {
                        getMinSnapshot(it.id)
                    } catch (_: Throwable) {
                        null
                    }
                }
                call.respond(list)
            }
            post("/deleteSnapshot") {
                val data = call.receive<ReqId>()
                val allSnapshots = DbSet.snapshotDao.query().first()
                val snapshot = allSnapshots.find { it.id == data.id }
                if (snapshot != null) {
                    SnapshotExt.removeSnapshot(data.id)
                    DbSet.snapshotDao.delete(snapshot)
                    call.respond(RpcOk("快照删除成功"))
                } else {
                    throw RpcError("快照不存在或已被删除")
                }
            }
            post("/updateSubscription") {
                val subscription =
                    RawSubscription.parse(call.receiveText(), json5 = false)
                        .copy(
                            id = LOCAL_HTTP_SUBS_ID,
                            name = "内存订阅",
                            version = 0,
                            author = "@gkd-kit/inspect"
                        )
                updateSubscription(subscription)
                DbSet.subsItemDao.insert((subsItemsFlow.value.find { s -> s.id == httpSubsItem.id }
                    ?: httpSubsItem).copy(mtime = System.currentTimeMillis()))
                call.respond(RpcOk())
            }
            post("/execSelector") {
                if (!A11yService.isRunning.value) {
                    throw RpcError("无障碍没有运行")
                }
                val gkdAction = call.receive<GkdAction>()
                call.respond(A11yRuleEngine.execAction(gkdAction))
            }
        }
    }
}

private fun getKtorCorsPlugin() = createApplicationPlugin(name = "KtorCorsPlugin") {
    onCall { call ->
        mapOf(
            HttpHeaders.AccessControlAllowOrigin to "*",
            HttpHeaders.AccessControlAllowMethods to "*",
            HttpHeaders.AccessControlAllowHeaders to "*",
            HttpHeaders.AccessControlExposeHeaders to "*",
            "Access-Control-Allow-Private-Network" to "true",
        ).forEach { (k, v) ->
            if (!call.response.headers.contains(k)) {
                call.response.header(k, v)
            }
        }
        if (call.request.httpMethod == HttpMethod.Options) {
            call.respond("all-cors-ok")
        }
    }
}

private fun getKtorErrorPlugin() = createApplicationPlugin(name = "KtorErrorPlugin") {
    onCall { call ->
        if (call.request.uri == "/" || call.request.uri.startsWith("/api/")) {
            Log.d("Ktor", "onCall: ${call.request.origin.remoteAddress} -> ${call.request.uri}")
        }
    }
    on(CallFailed) { call, cause ->
        when (cause) {
            is RpcError -> {
                // 主动抛出的错误
                LogUtils.d(call.request.uri, cause.message)
                call.respond(cause)
            }

            is Exception -> {
                // 未知错误
                LogUtils.d(call.request.uri, cause.message)
                cause.printStackTrace()
                call.respond(RpcError(message = cause.message ?: "unknown error", unknown = true))
            }

            else -> {
                cause.printStackTrace()
            }
        }
    }
}
