package com.yshs.gkd.ui

import kotlinx.coroutines.flow.MutableStateFlow
import com.yshs.gkd.db.DbSet
import com.yshs.gkd.ui.component.ShowGroupState
import com.yshs.gkd.ui.share.BaseViewModel

class SubsAppGroupListVm(val route: SubsAppGroupListRoute) : BaseViewModel() {

    val subsFlow = mapSafeSubs(route.subsItemId)
    val subsAppFlow = subsFlow.mapNew { it.getApp(route.appId) }

    val subsConfigsFlow = DbSet.subsConfigDao.queryAppGroupTypeConfig(route.subsItemId, route.appId)
        .stateInit(emptyList())

    val categoryConfigsFlow = DbSet.categoryConfigDao.queryConfig(route.subsItemId)
        .stateInit(emptyList())


    val isSelectedModeFlow = MutableStateFlow(false)
    val selectedDataSetFlow = MutableStateFlow(emptySet<ShowGroupState>())

    val focusGroupFlow = route.focusGroupKey?.let {
        MutableStateFlow<Triple<Long, String?, Int>?>(
            Triple(
                route.subsItemId,
                route.appId,
                route.focusGroupKey
            )
        )
    }
}