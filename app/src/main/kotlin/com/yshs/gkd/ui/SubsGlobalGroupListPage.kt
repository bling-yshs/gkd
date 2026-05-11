package com.yshs.gkd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import com.yshs.gkd.db.DbSet
import com.yshs.gkd.ui.component.AnimationFloatingActionButton
import com.yshs.gkd.ui.component.BatchActionButtonGroup
import com.yshs.gkd.ui.component.EmptyText
import com.yshs.gkd.ui.component.PerfIcon
import com.yshs.gkd.ui.component.PerfIconButton
import com.yshs.gkd.ui.component.PerfTopAppBar
import com.yshs.gkd.ui.component.RuleGroupCard
import com.yshs.gkd.ui.component.TowLineText
import com.yshs.gkd.ui.component.animateListItem
import com.yshs.gkd.ui.component.toGroupState
import com.yshs.gkd.ui.component.useListScrollState
import com.yshs.gkd.ui.component.waitResult
import com.yshs.gkd.ui.icon.BackCloseIcon
import com.yshs.gkd.ui.share.ListPlaceholder
import com.yshs.gkd.ui.share.LocalMainViewModel
import com.yshs.gkd.ui.share.noRippleClickable
import com.yshs.gkd.ui.style.EmptyHeight
import com.yshs.gkd.ui.style.scaffoldPadding
import com.yshs.gkd.util.getUpDownTransform
import com.yshs.gkd.util.launchAsFn
import com.yshs.gkd.util.switchItem
import com.yshs.gkd.util.throttle
import com.yshs.gkd.util.toast
import com.yshs.gkd.util.updateSubscription


@Serializable
data class SubsGlobalGroupListRoute(val subsItemId: Long, val focusGroupKey: Int? = null) : NavKey

@Composable
fun SubsGlobalGroupListPage(route: SubsGlobalGroupListRoute) {
    val subsItemId = route.subsItemId
    val focusGroupKey = route.focusGroupKey

    val mainVm = LocalMainViewModel.current
    val vm = viewModel { SubsGlobalGroupListVm(route) }
    val subs = vm.subsRawFlow.collectAsState().value
    val subsConfigs by vm.subsConfigsFlow.collectAsState()

    val editable = subsItemId < 0
    val globalGroups = subs.globalGroups

    val isSelectedMode = vm.isSelectedModeFlow.collectAsState().value
    val selectedDataSet = vm.selectedDataSetFlow.collectAsState().value
    LaunchedEffect(key1 = isSelectedMode) {
        if (!isSelectedMode) {
            vm.selectedDataSetFlow.value = emptySet()
        }
    }
    LaunchedEffect(key1 = selectedDataSet.isEmpty()) {
        if (selectedDataSet.isEmpty()) {
            vm.isSelectedModeFlow.value = false
        }
    }
    BackHandler(isSelectedMode) {
        vm.isSelectedModeFlow.value = false
    }

    val resetKey = rememberSaveable { mutableIntStateOf(0) }
    val (scrollBehavior, listState) = useListScrollState(resetKey, globalGroups.isEmpty())
    if (focusGroupKey != null) {
        LaunchedEffect(null) {
            if (vm.focusGroupFlow?.value != null) {
                val i = globalGroups.indexOfFirst { it.key == focusGroupKey }
                if (i >= 0) {
                    listState.scrollToItem(i)
                }
            }
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(scrollBehavior = scrollBehavior, navigationIcon = {
                IconButton(onClick = throttle {
                    if (isSelectedMode) {
                        vm.isSelectedModeFlow.value = false
                    } else {
                        mainVm.popPage()
                    }
                }) {
                    BackCloseIcon(backOrClose = !isSelectedMode)
                }
            }, title = {
                val titleModifier = Modifier.noRippleClickable { resetKey.intValue++ }
                if (isSelectedMode) {
                    Text(
                        modifier = titleModifier,
                        text = selectedDataSet.size.toString(),
                    )
                } else {
                    TowLineText(
                        modifier = titleModifier,
                        title = subs.name,
                        subtitle = "全局规则"
                    )
                }
            }, actions = {
                var expanded by remember { mutableStateOf(false) }
                AnimatedContent(
                    targetState = isSelectedMode,
                    transitionSpec = { getUpDownTransform() },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    if (it) {
                        Row {
                            BatchActionButtonGroup(vm, selectedDataSet)
                            if (editable) {
                                PerfIconButton(
                                    imageVector = PerfIcon.Delete,
                                    onClick = throttle(
                                        vm.viewModelScope.launchAsFn(
                                            Dispatchers.Default
                                        ) {
                                            mainVm.dialogFlow.waitResult(
                                                title = "删除规则",
                                                text = "删除当前所选规则?",
                                                error = true,
                                            )
                                            val keys = selectedDataSet.mapNotNull { g ->
                                                g.groupKey
                                            }
                                            vm.isSelectedModeFlow.value = false
                                            updateSubscription(
                                                subs.copy(
                                                    globalGroups = globalGroups.filterNot { g ->
                                                        keys.contains(g.key)
                                                    }
                                                )
                                            )
                                            DbSet.subsConfigDao.batchDeleteGlobalGroupConfig(
                                                subsItemId,
                                                keys
                                            )
                                            toast("删除成功")
                                        })
                                )
                            }
                            PerfIconButton(
                                imageVector = PerfIcon.MoreVert,
                                onClick = {
                                    expanded = true
                                })
                        }
                    }
                }
                if (isSelectedMode) {
                    Box(
                        modifier = Modifier
                            .wrapContentSize(Alignment.TopStart)
                    ) {
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(text = "全选")
                                },
                                onClick = {
                                    expanded = false
                                    vm.selectedDataSetFlow.value = globalGroups.map {
                                        it.toGroupState(
                                            subsId = subsItemId,
                                        )
                                    }.toSet()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(text = "反选")
                                },
                                onClick = {
                                    expanded = false
                                    val newSelectedIds = globalGroups.map {
                                        it.toGroupState(
                                            subsId = subsItemId,
                                        )
                                    }.toSet() - selectedDataSet
                                    vm.selectedDataSetFlow.value = newSelectedIds
                                }
                            )
                        }
                    }
                }
            })
        },
        floatingActionButton = {
            if (editable) {
                AnimationFloatingActionButton(
                    visible = !isSelectedMode,
                    onClick = {
                        mainVm.navigatePage(
                            UpsertRuleGroupRoute(
                                subsId = subsItemId,
                                groupKey = null,
                                appId = null,
                            )
                        )
                    },
                    imageVector = PerfIcon.Add,
                    contentDescription = "添加规则"
                )
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.scaffoldPadding(paddingValues),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(globalGroups, { g -> g.key }) { group ->
                val subsConfig = subsConfigs.find { it.groupKey == group.key }
                RuleGroupCard(
                    modifier = Modifier.animateListItem(),
                    subs = subs,
                    appId = null,
                    group = group,
                    focusGroupFlow = vm.focusGroupFlow,
                    subsConfig = subsConfig,
                    categoryConfig = null,
                    isSelectedMode = isSelectedMode,
                    isSelected = selectedDataSet.any { it.groupKey == group.key },
                    onLongClick = {
                        if (globalGroups.size > 1) {
                            vm.isSelectedModeFlow.value = true
                            vm.selectedDataSetFlow.value = setOf(
                                group.toGroupState(subsId = subsItemId)
                            )
                        }
                    },
                    onSelectedChange = {
                        vm.selectedDataSetFlow.value = selectedDataSet.switchItem(
                            group.toGroupState(subsId = subsItemId)
                        )
                    }
                )
            }
            item(ListPlaceholder.KEY, ListPlaceholder.TYPE) {
                Spacer(modifier = Modifier.height(EmptyHeight))
                if (globalGroups.isEmpty()) {
                    EmptyText(text = "暂无规则")
                }
            }
        }
    }
}
