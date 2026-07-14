package com.hermes.android.presentation.sessionlist

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.android.ui.settings.LocaleManager
import com.hermes.android.ui.settings.strEnZh
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    onBack: () -> Unit,
    onSessionCreated: (String) -> Unit,
    viewModel: SessionListViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    var titleText by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }
    var attachmentType by remember { mutableStateOf<String?>(null) }
    var attachmentName by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAttachmentMenu by remember { mutableStateOf(false) }

    // Camera temp file
    val photoDir = remember { File(context.cacheDir, "newsession_camera").apply { mkdirs() } }
    val photoFile = remember { File(photoDir, "photo_${System.currentTimeMillis()}.jpg") }
    val photoUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    }

    fun setAttachment(uri: Uri, type: String) {
        attachmentUri = uri
        attachmentType = type
        attachmentName = getDisplayName(context, uri)
        errorMessage = null
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { setAttachment(it, "image") } }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { setAttachment(it, "video") } }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { setAttachment(it, "file") } }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) setAttachment(photoUri, "image") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) cameraLauncher.launch(photoUri) }

    val canSend = (messageText.isNotBlank() || attachmentUri != null) && !isSending

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strEnZh("New session", "新建会话")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strEnZh("Back", "返回"))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title (optional)
            OutlinedTextField(
                value = titleText,
                onValueChange = { titleText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(strEnZh("Title (optional)", "标题（可选）")) },
                singleLine = true
            )

            // Message / caption
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it; errorMessage = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = {
                    Text(
                        if (attachmentUri != null) strEnZh("Caption (optional)", "附带文字（可选）") else strEnZh("Type a message...", "输入消息...")
                    )
                },
                maxLines = Int.MAX_VALUE,
                isError = errorMessage != null
            )

            // Attachment preview
            if (attachmentUri != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Attachment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = attachmentName ?: strEnZh("Attachment", "附件"),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                    IconButton(onClick = {
                        attachmentUri = null
                        attachmentType = null
                        attachmentName = null
                    }) {
                        Icon(Icons.Default.Close, contentDescription = strEnZh("Remove attachment", "移除附件"))
                    }
                }
            }

            // Error message
            if (errorMessage != null) {
                Text(
                    errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Attachment button + Send button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attachment button with dropdown
                Box {
                    IconButton(
                        onClick = { showAttachmentMenu = true },
                        enabled = !isSending
                    ) {
                        Icon(Icons.Default.Add, contentDescription = strEnZh("Add attachment", "添加附件"))
                    }
                    DropdownMenu(
                        expanded = showAttachmentMenu,
                        onDismissRequest = { showAttachmentMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(strEnZh("Send image", "发送图像")) },
                            onClick = {
                                showAttachmentMenu = false
                                imagePickerLauncher.launch("image/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strEnZh("Send video", "发送视频")) },
                            onClick = {
                                showAttachmentMenu = false
                                videoPickerLauncher.launch("video/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strEnZh("Send file", "发送文件")) },
                            onClick = {
                                showAttachmentMenu = false
                                filePickerLauncher.launch(arrayOf("*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strEnZh("Take photo", "拍照")) },
                            onClick = {
                                showAttachmentMenu = false
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )
                    }
                }

                // Send button
                Button(
                    onClick = {
                        isSending = true
                        errorMessage = null
                        viewModel.createNewSession(
                            message = messageText,
                            title = titleText.takeIf { it.isNotBlank() },
                            attachmentUri = attachmentUri,
                            attachmentType = attachmentType,
                            context = context
                        ) { success ->
                            isSending = false
                            if (success) {
                                onBack()
                            } else {
                                errorMessage = strEnZh(LocaleManager.currentLocale(), "Send failed, please try again", "发送失败，请重试")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = canSend
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (isSending) strEnZh("Sending...", "发送中...") else strEnZh("Send & create session", "发送并创建会话"))
                }
            }
        }
    }
}

private fun getDisplayName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    return cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) it.getString(idx) else null
        } else null
    }
}
