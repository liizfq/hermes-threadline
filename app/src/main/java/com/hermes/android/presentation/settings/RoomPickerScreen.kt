package com.hermes.android.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.android.ui.settings.strEnZh

/**
 * Secondary "Rooms" screen reached from [SettingsScreen].
 *
 * Hosts the full room checkbox list (loading / empty / per-row checkbox / the
 * "at least one room" hint). Shares its [SettingsViewModel] instance with the
 * parent Settings route so the push-room set and the loaded room list are not
 * fetched twice. Toggling a checkbox persists immediately (no Save button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomPickerScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel
) {
    val availableRooms by viewModel.availableRooms.collectAsState()
    val roomsLoading by viewModel.roomsLoading.collectAsState()
    val pushRoomIds by viewModel.pushRoomIds.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strEnZh("Rooms", "房间")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strEnZh("Back", "返回")
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                strEnZh(
                    "Select rooms to receive push notifications",
                    "选择要接收推送通知的房间"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                roomsLoading -> {
                    Text(
                        strEnZh("Loading rooms...", "加载房间列表..."),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                availableRooms.isEmpty() -> {
                    Text(
                        strEnZh("No rooms joined", "未加入任何房间"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> {
                    availableRooms.forEach { room ->
                        val checked = room.roomId in pushRoomIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { viewModel.togglePushRoom(room.roomId) }
                            )
                            Text(
                                room.displayName,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    if (pushRoomIds.size <= 1) {
                        Text(
                            strEnZh("Please select at least one room", "请至少选择一个房间"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
