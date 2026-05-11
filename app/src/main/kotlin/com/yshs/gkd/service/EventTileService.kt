package com.yshs.gkd.service

class EventTileService : BaseTileService() {
    override val activeFlow = EventService.isRunning

    init {
        onTileClicked {
            if (EventService.isRunning.value) {
                EventService.stop()
            } else {
                EventService.start()
            }
        }
    }
}