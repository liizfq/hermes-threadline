package com.hermes.android.presentation.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.android.push.PushChannel
import com.hermes.android.ui.settings.ChatFontScaleState
import com.hermes.android.ui.settings.ChatFontSize
import com.hermes.android.ui.settings.LocaleManager
import com.hermes.android.ui.settings.LocalChatFontScale
import com.hermes.android.ui.settings.strEnZh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val boundRoomId by viewModel.boundRoomId.collectAsState()
    val logoutState by viewModel.logoutState.collectAsState()
    val pushState by viewModel.pushState.collectAsState()
    val language by viewModel.language.collectAsState()
    var roomInput by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var pendingEnablePush by remember { mutableStateOf(false) }

    // Chat font scale — backed by ChatFontSize (SharedPreferences).
    var fontScale by remember { mutableFloatStateOf(ChatFontSize.get(context)) }

    val needsNotificationPermission: Boolean = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun isNotificationGranted(): Boolean {
        if (!needsNotificationPermission) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (pendingEnablePush) {
                viewModel.updatePush { it.copy(enabled = true) }
                pendingEnablePush = false
            }
        } else {
            showPermissionRationale = true
            if (pendingEnablePush) {
                pendingEnablePush = false
            }
        }
    }

    fun requestNotificationPermission(thenEnablePush: Boolean) {
        if (thenEnablePush) pendingEnablePush = true
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    // Update roomInput when boundRoomId changes
    LaunchedEffect(boundRoomId) {
        if (roomInput.isEmpty() && boundRoomId != null) {
            roomInput = boundRoomId!!
        }
    }

    // Handle logout success
    LaunchedEffect(logoutState) {
        if (logoutState is SettingsViewModel.LogoutState.Success) {
            onLogout()
        }
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(strEnZh("Confirm Logout", "确认登出")) },
            text = { Text(strEnZh("You will need to log in again to continue using the app. Are you sure?", "登出后需要重新登录才能使用。确定要登出吗？")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                    }
                ) {
                    Text(strEnZh("Logout", "登出"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(strEnZh("Cancel", "取消"))
                }
            }
        )
    }

    // Notification permission rationale dialog
    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text(strEnZh("Notification Permission Required", "需要通知权限")) },
            text = {
                Text(
                    strEnZh(
                        "Push notifications need notification permission to alert you when you receive a message.\n" +
                            "You denied the permission in the system dialog. Please go to Settings → Notifications to enable it manually and try again.",
                        "推送功能需要通知权限才能在收到消息时提醒你。\n" +
                            "你已在系统弹窗中拒绝了权限，请到「设置 → 通知」手动开启后再次尝试。"
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionRationale = false
                        openAppNotificationSettings()
                    }
                ) {
                    Text(strEnZh("Go to Settings", "去设置"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationale = false }) {
                    Text(strEnZh("Cancel", "取消"))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strEnZh("Settings", "设置")) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- Account ----
            Text(strEnZh("Account", "账号"), style = MaterialTheme.typography.titleMedium)
            Text(strEnZh("User: ${viewModel.userId ?: "Not logged in"}", "用户: ${viewModel.userId ?: "未登录"}"))
            Text(strEnZh("Server: ${viewModel.homeserverUrl ?: "Not configured"}", "服务器: ${viewModel.homeserverUrl ?: "未配置"}"))

            HorizontalDivider()

            // ---- Language ----
            Text(strEnZh("Language", "语言"), style = MaterialTheme.typography.titleMedium)
            LanguageSelector(
                selected = language,
                onSelect = { viewModel.setLanguage(it) }
            )

            HorizontalDivider()

            // ---- Message font size ----
            Text(strEnZh("Message Display", "消息显示"), style = MaterialTheme.typography.titleMedium)

            Text(
                strEnZh("Font size: ${(fontScale * 100).toInt()}%", "字体大小: ${(fontScale * 100).toInt()}%"),
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = fontScale,
                onValueChange = { newScale ->
                    fontScale = newScale
                    ChatFontScaleState.state.floatValue = newScale
                    ChatFontSize.set(context, newScale)
                },
                valueRange = ChatFontSize.MIN_SCALE..ChatFontSize.MAX_SCALE,
                steps = 19,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(strEnZh("Small", "小"), style = MaterialTheme.typography.bodySmall)
                Text(strEnZh("Standard", "标准"), style = MaterialTheme.typography.bodySmall)
                Text(strEnZh("Large", "大"), style = MaterialTheme.typography.bodySmall)
            }

            // Live preview
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = strEnZh("Preview: This is a message bubble text preview effect.", "预览：这是一条消息气泡的文本预览效果。"),
                    fontSize = (14 * fontScale).sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            HorizontalDivider()

            // ---- Room configuration ----
            Text(strEnZh("Room Configuration", "房间配置"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = roomInput,
                onValueChange = {
                    roomInput = it
                    saved = false
                },
                label = { Text(strEnZh("Bound Room ID", "绑定房间 ID")) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("!roomid:server.com") }
            )
            Button(
                onClick = {
                    viewModel.saveBoundRoom(roomInput)
                    saved = true
                },
                enabled = roomInput.isNotBlank()
            ) {
                Text(strEnZh("Save", "保存"))
            }

            if (saved) {
                Text(strEnZh("✓ Saved", "✓ 已保存"), color = MaterialTheme.colorScheme.primary)
            }

            if (boundRoomId != null) {
                Text(
                    strEnZh("Currently bound: $boundRoomId", "当前绑定: $boundRoomId"),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            HorizontalDivider()

            // ---- Push notifications ----
            Text(strEnZh("Push Notifications", "消息推送"), style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(strEnZh("Enable Push", "启用推送"), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = pushState.enabled,
                    onCheckedChange = { v ->
                        if (v && !isNotificationGranted()) {
                            requestNotificationPermission(thenEnablePush = true)
                        } else {
                            viewModel.updatePush { it.copy(enabled = v) }
                        }
                    }
                )
            }

            Text(
                strEnZh(
                    "✅ Push AI's final reply on reaction; ⏳ Working timeout reminder; 👀 No disturbance.",
                    "✅ reaction 时推送 AI 最后一条回复；⏳ Working 超时提醒；👀 不打扰。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(strEnZh("Push Channel", "推送渠道"), style = MaterialTheme.typography.bodyMedium)
            PushChannelSelector(
                selected = pushState.channel,
                enabled = pushState.enabled,
                onSelect = { ch -> viewModel.updatePush { it.copy(channel = ch) } }
            )

            Text(
                strEnZh("Timeout Threshold (minutes)", "超时阈值（分钟）"),
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = pushState.timeoutMinutes.toString(),
                onValueChange = { raw ->
                    val n = raw.toIntOrNull()
                    if (n != null && n > 0) {
                        viewModel.updatePush { it.copy(timeoutMinutes = n) }
                    }
                },
                label = {
                    Text(
                        strEnZh(
                            "Push a timeout reminder if processing exceeds this duration",
                            "超过该时长未完成则推送超时提醒"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = pushState.enabled,
                singleLine = true
            )

            HorizontalDivider()

            // ---- ntfy configuration ----
            Text(strEnZh("ntfy Configuration", "ntfy 配置"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = pushState.ntfyServerUrl,
                onValueChange = { v -> viewModel.updatePush { it.copy(ntfyServerUrl = v) } },
                label = { Text(strEnZh("ntfy Server Address", "ntfy 服务器地址")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = pushState.enabled,
                singleLine = true,
                placeholder = { Text("https://ntfy.example.com") }
            )
            Text(
                strEnZh(
                    "Topic is automatically assigned by the ntfy server; no need to fill in manually.",
                    "Topic 由 ntfy 服务器自动分配，无需手动填写"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = { viewModel.savePushSettings() },
                enabled = pushState.enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strEnZh("Save Push Settings", "保存推送设置"))
            }

            if (pushState.saved) {
                Text(
                    strEnZh("✓ Push settings saved", "✓ 推送设置已保存"),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider()

            // Logout button
            Button(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                enabled = logoutState !is SettingsViewModel.LogoutState.Loading
            ) {
                if (logoutState is SettingsViewModel.LogoutState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onError
                    )
                } else {
                    Text(strEnZh("Logout", "登出账号"))
                }
            }

            if (logoutState is SettingsViewModel.LogoutState.Error) {
                Text(
                    text = (logoutState as SettingsViewModel.LogoutState.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PushChannelSelector(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    val options = listOf(
        PushChannel.SYSTEM to strEnZh("System Notification", "系统通知"),
        PushChannel.NTFY to "ntfy",
        PushChannel.BOTH to strEnZh("Both", "两者")
    )
    Column {
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    enabled = enabled
                )
                Text(label)
            }
        }
    }
}

@Composable
private fun LanguageSelector(
    selected: String,
    onSelect: (String) -> Unit
) {
    val options = listOf(
        LocaleManager.EN to "English",
        LocaleManager.ZH to "中文"
    )
    Column {
        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == value,
                    onClick = { onSelect(value) }
                )
                Text(label)
            }
        }
    }
}
