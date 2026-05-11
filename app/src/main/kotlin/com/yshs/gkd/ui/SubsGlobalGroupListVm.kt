package com.yshs.gkd.ui

import kotlinx.coroutines.flow.MutableStateFlow
import com.yshs.gkd.db.DbSet
import com.yshs.gkd.ui.component.ShowGroupState
import com.yshs.gkd.ui.share.BaseViewModel

class SubsGlobalGroupListVm(val route: SubsGlobalGroupListRoute) : BaseViewModel() {
    val subsRawFlow = mapSafeSubs(route.subsItemId)

    val subsConfigsFlow = DbSet.subsConfigDao.queryGlobalGroupTypeConfig(route.subsItemId)
        .stateInit(emptyList())

    val isSelectedModeFlow = MutableStateFlow(false)
    val selectedDataSetFlow = MutableStateFlow(emptySet<ShowGroupState>())
    val focusGroupFlow = route.focusGroupKey?.let {
        MutableStateFlow<Triple<Long, String?, Int>?>(
            Triple(
                route.subsItemId,
                null,
                route.focusGroupKey
            )
        )
    }
}