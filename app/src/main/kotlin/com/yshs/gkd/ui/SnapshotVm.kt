package com.yshs.gkd.ui

import com.yshs.gkd.db.DbSet
import com.yshs.gkd.ui.share.BaseViewModel

class SnapshotVm : BaseViewModel() {
    val snapshotsState = DbSet.snapshotDao.query().attachLoad()
        .stateInit(emptyList())
}