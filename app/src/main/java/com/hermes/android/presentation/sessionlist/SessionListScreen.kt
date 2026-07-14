package com.hermes.android.presentation.sessionlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.android.domain.model.Session
import com.hermes.android.presentation.UiState
import com.hermes.android.ui.settings.strEnZh
import com.hermes.android.ui.theme.AgentColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    onSessionClick: (String) -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit = {},
    viewModel: SessionListViewModel = hiltViewModel()
) {
    val sessions by viewModel.filteredSessions.collectAsState()
    val boundRoomId by viewModel.boundRoomId.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = viewModel::onSearchQueryChange,
                            placeholder = { Text(strEnZh("Search sessions...", "搜索会话...")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    } else {
                        Text("Hermes Agent")
                    }
                },
                navigationIcon = {
                    if (!isSearchActive) {
                        IconButton(onClick = { }) {
                            Icon(Icons.Default.Menu, contentDescription = strEnZh("Menu", "菜单"))
                        }
                    }
                },
                actions = {
                    if (isSearchActive) {
                        IconButton(onClick = viewModel::closeSearch) {
                            Icon(Icons.Default.Close, contentDescription = strEnZh("Close search", "关闭搜索"))
                        }
                    } else {
                        IconButton(onClick = onNewSession) {
                            Icon(Icons.Default.Edit, contentDescription = strEnZh("New session", "新建会话"))
                        }
                        IconButton(onClick = viewModel::openSearch) {
                            Icon(Icons.Default.Search, contentDescription = strEnZh("Search", "搜索"))
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = strEnZh("Settings", "设置"))
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (boundRoomId == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(strEnZh("Please bind a room first", "请先绑定房间"), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(strEnZh("Tap the settings button in the top-right corner to configure a room ID", "点击右上角设置按钮配置房间 ID"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onSettings) {
                        Text(strEnZh("Go to Settings", "去设置"))
                    }
                }
            }
        } else {
            when (val state = sessions) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            Text(
                                if (isSearchActive) strEnZh("No matching sessions found", "未找到匹配的会话") else strEnZh("No sessions yet", "暂无会话"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val sessionTitles = remember(state.data) { viewModel.getSessionTitles() }
                        var pendingDelete by remember { mutableStateOf<Session?>(null) }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(state.data) { session ->
                                SessionCard(
                                    session = session,
                                    onClick = {
                                        viewModel.markSessionRead(session.id)
                                        onSessionClick(session.id)
                                    },
                                    onDelete = { pendingDelete = session },
                                    unreadCount = session.unreadCount,
                                    customTitle = sessionTitles[session.id]
                                )
                            }
                        }

                        // Delete confirmation dialog
                        pendingDelete?.let { session ->
                            val title = sessionTitles[session.id] ?: session.title
                            AlertDialog(
                                onDismissRequest = { pendingDelete = null },
                                title = { Text(strEnZh("Delete session", "删除会话")) },
                                text = {
                                    Column {
                                        Text(strEnZh("Session: $title", "会话: $title"))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            strEnZh("This session will be removed from the list. This action cannot be undone.", "删除后该会话将从列表中移除，此操作不可撤销。"),
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            viewModel.deleteSession(session)
                                            pendingDelete = null
                                        }
                                    ) { Text(strEnZh("Confirm", "确认"), color = MaterialTheme.colorScheme.error) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingDelete = null }) {
                                        Text(strEnZh("Cancel", "取消"))
                                    }
                                }
                            )
                        }
                    }
                }
                is UiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(
                            strEnZh("Error: ${state.message}", "错误: ${state.message}"),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
