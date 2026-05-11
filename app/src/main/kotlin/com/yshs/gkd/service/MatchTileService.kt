package com.yshs.gkd.service

import com.yshs.gkd.store.storeFlow
import com.yshs.gkd.store.switchStoreEnableMatch
import com.yshs.gkd.util.mapState

class MatchTileService : BaseTileService() {
    override val activeFlow = storeFlow.mapState(scope) { it.enableMatch }

    init {
        onTileClicked { switchStoreEnableMatch() }
    }
}