package com.hermes.android.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import javax.inject.Inject

/**
 * Factory that creates [ActiveThreadImpl] instances. Injected into [SessionRepository]
 * so it can open/close active thread handles on demand without being a singleton map.
 */
class ActiveThreadFactory @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend fun create(room: Room, threadRootId: String): ActiveThreadImpl {
        val timeline = withContext(Dispatchers.IO) {
            room.timelineWithConfiguration(
                TimelineConfiguration(
                    focus = TimelineFocus.Thread(threadRootId),
                    filter = TimelineFilter.All,
                    internalIdPrefix = "Thread_$threadRootId",
                    dateDividerMode = DateDividerMode.DAILY,
                    trackReadReceipts = TimelineReadReceiptTracking.ALL_EVENTS,
                    reportUtds = false
                )
            )
        }
        return try {
            ActiveThreadImpl(
                room = room,
                threadRootId = threadRootId,
                settingsRepository = settingsRepository,
                timeline = timeline
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            timeline.close()
            throw e
        }
    }
}
