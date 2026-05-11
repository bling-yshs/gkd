package com.yshs.gkd.ui

import com.yshs.gkd.MainViewModel
import com.yshs.gkd.ui.share.BaseViewModel

class CrashReportVm : BaseViewModel() {
    val crashDataList = MainViewModel.instance.run {
        val v = tempCrashDataList
        tempCrashDataList = emptyList()
        v
    }
}