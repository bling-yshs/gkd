package com.yshs.gkd.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import com.yshs.gkd.MainActivity
import com.yshs.gkd.store.blockMatchAppListFlow
import com.yshs.gkd.ui.component.MultiTextField
import com.yshs.gkd.ui.component.PerfIcon
import com.yshs.gkd.ui.component.PerfIconButton
import com.yshs.gkd.ui.component.PerfTopAppBar
import com.yshs.gkd.ui.component.waitResult
import com.yshs.gkd.ui.share.LocalMainViewModel
import com.yshs.gkd.ui.style.scaffoldPadding
import com.yshs.gkd.util.launchAsFn
import com.yshs.gkd.util.throttle
import com.yshs.gkd.util.toast

@Serializable
data object EditBlockAppListRoute : NavKey

@Composable
fun EditBlockAppListPage() {
    val mainVm = LocalMainViewModel.current
    val context = LocalActivity.current as MainActivity
    val vm = viewModel<EditBlockAppListVm>()
    val onBack = throttle(vm.viewModelScope.launchAsFn {
        if (vm.getChangedSet() != null) {
            context.justHideSoftInput()
            mainVm.dialogFlow.waitResult(
                title = "提示",
                text = "当前内容未保存，是否放弃编辑？",
            )
        } else {
            context.hideSoftInput()
        }
        mainVm.popPage()
    })
    BackHandler(onBack = onBack)
    Scaffold(modifier = Modifier, topBar = {
        PerfTopAppBar(
            modifier = Modifier.fillMaxWidth(),
            navigationIcon = {
                PerfIconButton(
                    imageVector = PerfIcon.ArrowBack,
                    onClick = onBack,
                )
            },
            title = { Text(text = "应用白名单") },
            actions = {
                PerfIconButton(
                    imageVector = PerfIcon.Save,
                    onClick = throttle(vm.viewModelScope.launchAsFn {
                        val newSet = vm.getChangedSet()
                        if (newSet != null) {
                            blockMatchAppListFlow.value = newSet
                            toast("更新成功")
                        } else {
                            toast("未修改")
                        }
                        context.hideSoftInput()
                        mainVm.popPage()
                    })
                )
            }
        )
    }) { contentPadding ->
        MultiTextField(
            modifier = Modifier.scaffoldPadding(contentPadding),
            textFlow = vm.textFlow,
            indicatorSize = vm.indicatorSizeFlow.collectAsState().value
        )
    }
}