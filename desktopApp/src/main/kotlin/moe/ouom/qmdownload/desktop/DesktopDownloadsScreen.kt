package moe.ouom.qmdownload.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.qmdownload.core.download.DownloadTaskRecord
import moe.ouom.qmdownload.core.download.DownloadTaskStatus

@Composable
fun DesktopDownloadsScreen(
    tasks: List<DownloadTaskRecord>,
    downloading: Boolean,
    maxConcurrent: Int,
    onMaxConcurrentChange: (Int) -> Unit,
    onBack: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
    onOpenFolder: (DownloadTaskRecord) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("返回音乐库") }
            Text("下载任务", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (downloading) {
                Button(onClick = onPauseAll) { Text("全部暂停") }
            } else {
                Button(onClick = onResumeAll, enabled = tasks.any { it.isPending() }) {
                    Text("全部继续")
                }
            }
        }
        Text(
            "共 ${tasks.size} 个任务；暂停后保留 .part，继续时从断点续传",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("并行任务", style = MaterialTheme.typography.bodySmall)
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text("$maxConcurrent 个")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    (1..6).forEach { count ->
                        DropdownMenuItem(
                            text = { Text("$count 个任务") },
                            onClick = {
                                expanded = false
                                onMaxConcurrentChange(count)
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        if (tasks.isEmpty()) {
            Text(
                "暂无下载任务",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                DownloadTaskRow(
                    task = task,
                    onPause = { onPauseTask(task.id) },
                    onResume = { onResumeTask(task.id) },
                    onRetry = { onRetryTask(task.id) },
                    onRemove = { onRemoveTask(task.id) },
                    onOpenFolder = { onOpenFolder(task) },
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    task: DownloadTaskRecord,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.SemiBold)
                Text(
                    "${task.artist} · ${task.qualityLabel()} · ${task.statusLabel()}" +
                        if (task.retryCount > 0) " · 重试 ${task.retryCount}/3" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (task.status) {
                    DownloadTaskStatus.DOWNLOADING -> {
                        OutlinedButton(onClick = onPause) { Text("暂停") }
                    }
                    DownloadTaskStatus.PAUSED,
                    DownloadTaskStatus.QUEUED -> {
                        Button(onClick = onResume) { Text("继续") }
                    }
                    DownloadTaskStatus.FAILED -> {
                        OutlinedButton(onClick = onRetry) { Text("重试") }
                    }
                    DownloadTaskStatus.COMPLETED -> Unit
                }
                OutlinedButton(onClick = onOpenFolder) { Text("目录") }
                OutlinedButton(onClick = onRemove) { Text("删除") }
            }
        }
        if (task.status == DownloadTaskStatus.DOWNLOADING ||
            task.status == DownloadTaskStatus.PAUSED
        ) {
            val fraction = if (task.totalBytes > 0) {
                (task.downloadedBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(
                buildString {
                    append("${formatBytes(task.downloadedBytes)}")
                    if (task.totalBytes > 0) append(" / ${formatBytes(task.totalBytes)}")
                    if (task.totalBytes > 0) append("  ${(fraction * 100).toInt()}%")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        task.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(task.target, style = MaterialTheme.typography.bodySmall)
    }
}

private fun DownloadTaskRecord.isPending(): Boolean =
    status == DownloadTaskStatus.QUEUED ||
        status == DownloadTaskStatus.PAUSED ||
        status == DownloadTaskStatus.DOWNLOADING

private fun DownloadTaskRecord.statusLabel(): String = when (status) {
    DownloadTaskStatus.QUEUED -> "等待"
    DownloadTaskStatus.DOWNLOADING -> "下载中"
    DownloadTaskStatus.PAUSED -> "已暂停"
    DownloadTaskStatus.FAILED -> "失败"
    DownloadTaskStatus.COMPLETED -> "已完成"
}

private fun DownloadTaskRecord.qualityLabel(): String = when (quality) {
    "lossless" -> "无损"
    "exhigh" -> "320k"
    else -> "128k"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1fKB", kb)
    return String.format("%.1fMB", kb / 1024.0)
}
