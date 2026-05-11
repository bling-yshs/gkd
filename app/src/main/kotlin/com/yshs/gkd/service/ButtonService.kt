package com.yshs.gkd.service

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import com.yshs.gkd.appScope
import com.yshs.gkd.notif.StopServiceReceiver
import com.yshs.gkd.notif.buttonNotif
import com.yshs.gkd.permission.canDrawOverlaysState
import com.yshs.gkd.ui.component.PerfIcon
import com.yshs.gkd.util.SnapshotExt
import com.yshs.gkd.util.launchTry
import com.yshs.gkd.util.startForegroundServiceByClass
import com.yshs.gkd.util.stopServiceByClass

class ButtonService : OverlayWindowService(
    positionKey = "button"
) {
    override fun onClickView() = appScope.launchTry {
        SnapshotExt.captureSnapshot()
    }.let { }

    override fun onLongClickView() = stopSelf()

    @Composable
    override fun ComposeContent() {
        val alpha = 0.75f
        PerfIcon(
            imageVector = PerfIcon.CenterFocusWeak,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha))
                .size(40.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        )
    }

    init {
        useAliveFlow(isRunning)
        useAliveToast("快照按钮服务")
        onCreated { buttonNotif.notifyService() }
        StopServiceReceiver.autoRegister()
    }

    companion object {
        val isRunning = MutableStateFlow(false)
        fun start() {
            if (!canDrawOverlaysState.checkOrToast()) return
            startForegroundServiceByClass(ButtonService::class)
        }

        fun stop() = stopServiceByClass(ButtonService::class)
    }
}