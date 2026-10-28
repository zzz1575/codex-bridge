package com.y2yy.codex.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.y2yy.codex.mobile.data.TurnInterruptionNotice

@Composable
internal fun TurnInterruptionCard(messageId: String, notice: TurnInterruptionNotice) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    var rawExpanded by rememberSaveable(messageId) { mutableStateOf(false) }
    OutlinedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("turn-interruption:$messageId")) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("turn-interruption-toggle:$messageId"), contentPadding = PaddingValues(14.dp)) {
            Icon(Icons.Outlined.PauseCircleOutline, null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text("本轮已中断", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起中断详情" else "展开中断详情")
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("用户已中断上一轮回复。", style = MaterialTheme.typography.bodyMedium)
            Text("已启动的工具可能仍在后台运行，部分命令也可能已经执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { rawExpanded = !rawExpanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("turn-interruption-raw-toggle:$messageId")) {
                Text(if (rawExpanded) "收起原始提示" else "查看原始提示", style = MaterialTheme.typography.labelSmall)
            }
            if (rawExpanded) SelectionContainer {
                Text(notice.raw, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("turn-interruption-raw:$messageId"))
            }
        }
    }
}
