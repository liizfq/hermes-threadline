package com.hermes.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hermes.android.ui.theme.AgentColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun CodeBlockView(
    code: String,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.4f).dp

    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(AgentColors.Background)
    ) {
        Box(
            modifier = Modifier
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll)
        ) {
            CodeTextView(
                code = code,
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 44.dp),
            )
        }
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(code))
                scope.launch {
                    copied = true
                    delay(2000)
                    copied = false
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(36.dp)
                .padding(top = 4.dp, end = 4.dp)
        ) {
            Icon(
                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy code",
                tint = AgentColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CodeTextView(
    code: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            android.widget.TextView(context).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 13f
                setTextColor(android.graphics.Color.rgb(0x1C, 0x1C, 0x1E))
                setTextIsSelectable(true)
                setLineSpacing(2f, 1f)
                text = code
            }
        },
        update = { tv ->
            if (tv.text?.toString() != code) {
                tv.text = code
            }
        },
    )
}
