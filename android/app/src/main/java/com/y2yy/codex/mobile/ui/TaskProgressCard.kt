package com.y2yy.codex.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.y2yy.codex.mobile.data.TaskProgressNotice

@Composable
internal fun TaskProgressCard(messageId: String, notice: TaskProgressNotice) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    var rawExpanded by rememberSaveable(messageId) { mutableStateOf(false) }
    OutlinedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("task-progress:$messageId")) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("task-progress-toggle:$messageId"), contentPadding = PaddingValues(14.dp)) {
            Icon(Icons.Outlined.ReceiptLong, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text("任务进度 · ${notice.updates.size} 项更新", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起任务进度" else "展开任务进度")
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            notice.updates.forEach { update ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${update.taskId} · ${update.status}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (update.detail.isNotEmpty()) Text(update.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("统筹自动回报 · 详细结果请查看统筹汇总", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { rawExpanded = !rawExpanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("task-progress-raw-toggle:$messageId")) {
                Text(if (rawExpanded) "收起原始通知" else "查看原始通知", style = MaterialTheme.typography.labelSmall)
            }
            if (rawExpanded) SelectionContainer {
                Text(notice.raw, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("task-progress-raw:$messageId"))
            }
        }
    }
}
