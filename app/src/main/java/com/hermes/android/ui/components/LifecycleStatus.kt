package com.hermes.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.android.ui.settings.strEnZh

enum class LifecycleStatus {
    PROCESSING,
    SUCCESS,
    FAILURE,
    NONE
}

@Composable
fun LifecycleStatusIndicator(
    status: LifecycleStatus,
    modifier: Modifier = Modifier
) {
    if (status == LifecycleStatus.NONE) return

    val (emoji, label, color) = when (status) {
        LifecycleStatus.PROCESSING -> Triple("👀", strEnZh("Processing", "处理中"), MaterialTheme.colorScheme.primary)
        LifecycleStatus.SUCCESS -> Triple("✅", strEnZh("Done", "完成"), MaterialTheme.colorScheme.primary)
        LifecycleStatus.FAILURE -> Triple("❌", strEnZh("Failed", "失败"), MaterialTheme.colorScheme.error)
        LifecycleStatus.NONE -> return
    }

    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = emoji, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
