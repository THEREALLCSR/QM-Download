package moe.ouom.qmdownload.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import moe.ouom.qmdownload.core.download.DownloadBatch
import moe.ouom.qmdownload.core.download.DownloadTaskRecord
import moe.ouom.qmdownload.core.download.DownloadTaskStatus
import moe.ouom.qmdownload.core.download.completedCount

@Composable
fun AndroidDownloadsScreen(
    tasks: List<DownloadTaskRecord>,
    batches: List<DownloadBatch>,
    authPausedCount: Int,
    maxConcurrent: Int,
    speedLimitKbps: Int,
    onMaxConcurrentChange: (Int) -> Unit,
    onSpeedLimitChange: (Int) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
    onPauseBatch: (String) -> Unit,
    onResumeBatch: (String) -> Unit,
    onRetryBatch: (String) -> Unit,
    onRemoveBatch: (String) -> Unit,
    onOpenBatchFolder: (DownloadBatch) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("返回音乐库") }
            Spacer(Modifier.width(4.dp))
            Text("下载任务", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onPauseAll) { Text("暂停") }
            Button(onClick = onResumeAll, enabled = tasks.any { it.isPending() }) {
                Text("继续")
            }
        }
        Text(
            "暂停保留 .part；继续时从断点续传；失败自动重试 3 次",
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("下载限速", style = MaterialTheme.typography.bodySmall)
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(speedLimitLabel(speedLimitKbps))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    listOf(0, 512, 1024, 2048).forEach { kbps ->
                        DropdownMenuItem(
                            text = { Text(speedLimitLabel(kbps)) },
                            onClick = {
                                expanded = false
                                onSpeedLimitChange(kbps)
                            },
                        )
                    }
                }
            }
            Text("可降低风控风险", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        if (authPausedCount > 0) {
            Text(
                "登录已过期，已暂停 $authPausedCount 个任务；重新登录后将自动继续",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (tasks.isEmpty() && batches.isEmpty()) {
            Text("暂无下载任务", modifier = Modifier.padding(24.dp))
            return@Column
        }
        val byBatch = tasks.groupBy { it.batchId }
        val sortedBatches = batches.sortedByDescending { it.createdAt }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sortedBatches.forEach { batch ->
                val group = byBatch[batch.id].orEmpty().sortedBy { it.seq }
                item(key = "batch_header_${batch.id}") {
                    BatchHeader(
                        batch = batch,
                        remaining = group.size,
                        onPause = { onPauseBatch(batch.id) },
                        onResume = { onResumeBatch(batch.id) },
                        onRetry = { onRetryBatch(batch.id) },
                        onOpenFolder = { onOpenBatchFolder(batch) },
                        onRemove = { onRemoveBatch(batch.id) },
                    )
                }
                items(group, key = { "task_${it.id}" }) { task ->
                    TaskItem(task, onPauseTask, onResumeTask, onRetryTask, onRemoveTask)
                }
            }
            val ungrouped = byBatch[null].orEmpty().sortedBy { it.seq }
            if (ungrouped.isNotEmpty()) {
                item(key = "ungrouped_header") {
                    Text(
                        "未分组任务（旧版本任务）",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                items(ungrouped, key = { "task_${it.id}" }) { task ->
                    TaskItem(task, onPauseTask, onResumeTask, onRetryTask, onRemoveTask)
                }
            }
        }
    }
}

@Composable
private fun BatchHeader(
    batch: DownloadBatch,
    remaining: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onOpenFolder: () -> Unit,
    onRemove: () -> Unit,
) {
    // 完成任务在 Service 里会被移除记录，故用 total - 剩余任务数估算完成数；
    // 与 Desktop 端共用 core 纯函数（调用方已按 batchId 过滤 remaining）
    val done = completedCount(batch.total, remaining)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${batch.playlistTitle} · 完成 $done/${batch.total}", fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(
            progress = if (batch.total > 0) done.toFloat() / batch.total else 0f,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Text(
            "删除批次仅移除批次与任务记录，不删除已下载音频",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(onClick = onPause) { Text("暂停") }
            OutlinedButton(onClick = onResume) { Text("继续") }
            OutlinedButton(onClick = onRetry) { Text("重试") }
            OutlinedButton(onClick = onOpenFolder) { Text("目录") }
            OutlinedButton(onClick = onRemove) { Text("删除") }
        }
    }
}

@Composable
private fun TaskItem(
    task: DownloadTaskRecord,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.SemiBold)
                Text(
                    "${task.artist} · ${task.statusLabel()}" +
                        if (task.retryCount > 0) " · 重试 ${task.retryCount}/3" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (task.status == DownloadTaskStatus.DOWNLOADING) {
                OutlinedButton(onClick = { onPauseTask(task.id) }) { Text("暂停") }
            } else if (task.status == DownloadTaskStatus.PAUSED ||
                task.status == DownloadTaskStatus.QUEUED
            ) {
                Button(onClick = { onResumeTask(task.id) }) { Text("继续") }
            } else if (task.status == DownloadTaskStatus.FAILED) {
                OutlinedButton(onClick = { onRetryTask(task.id) }) { Text("重试") }
            }
            OutlinedButton(onClick = { onRemoveTask(task.id) }) { Text("删除") }
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
                "${formatBytes(task.downloadedBytes)}" +
                    if (task.totalBytes > 0) {
                        " / ${formatBytes(task.totalBytes)}  ${(fraction * 100).toInt()}%"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        task.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Text(
            task.target,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun speedLimitLabel(kbps: Int): String = when (kbps) {
    512 -> "512KB/s"
    1024 -> "1MB/s"
    2048 -> "2MB/s"
    else -> "不限"
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

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1fKB", kb)
    return String.format("%.1fMB", kb / 1024.0)
}
