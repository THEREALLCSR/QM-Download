package moe.ouom.qmdownload.desktop

import androidx.compose.foundation.background
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
import moe.ouom.qmdownload.core.download.DownloadBatch
import moe.ouom.qmdownload.core.download.DownloadTaskRecord
import moe.ouom.qmdownload.core.download.DownloadTaskStatus
import moe.ouom.qmdownload.core.download.completedCount

@Composable
fun DesktopDownloadsScreen(
    tasks: List<DownloadTaskRecord>,
    batches: List<DownloadBatch>,
    authPausedCount: Int,
    downloading: Boolean,
    maxConcurrent: Int,
    onMaxConcurrentChange: (Int) -> Unit,
    speedLimitKbps: Int,
    onSpeedLimitChange: (Int) -> Unit,
    onBack: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
    onOpenFolder: (DownloadTaskRecord) -> Unit,
    onPauseBatch: (String) -> Unit,
    onResumeBatch: (String) -> Unit,
    onRetryBatch: (String) -> Unit,
    onRemoveBatch: (String) -> Unit,
    onOpenBatchFolder: (DownloadBatch) -> Unit,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("限速", style = MaterialTheme.typography.bodySmall)
            var speedExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { speedExpanded = true }) {
                    Text(speedLimitLabel(speedLimitKbps))
                }
                DropdownMenu(
                    expanded = speedExpanded,
                    onDismissRequest = { speedExpanded = false },
                ) {
                    listOf(0, 512, 1024, 2048).forEach { kbps ->
                        DropdownMenuItem(
                            text = { Text(speedLimitLabel(kbps)) },
                            onClick = {
                                speedExpanded = false
                                onSpeedLimitChange(kbps)
                            },
                        )
                    }
                }
            }
            Text("限速可降低风控风险，对正在下载的任务立即生效", style = MaterialTheme.typography.bodySmall)
        }
        // 登录过期自动暂停提示：醒目警告条，重新登录成功后由 Main 自动恢复
        if (authPausedCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "登录已过期，已暂停 $authPausedCount 个任务；重新登录后将自动继续",
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(10.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text(
            "删除批次仅移除批次与任务记录（含 .part），不会删除已下载的音频文件",
            style = MaterialTheme.typography.bodySmall,
        )
        if (tasks.isEmpty() && batches.isEmpty()) {
            Text(
                "暂无下载任务",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }
        val grouped = tasks.groupBy { it.batchId }
        val orderedBatches = batches.sortedByDescending { it.createdAt }
        val ungrouped = grouped[null].orEmpty().sortedBy { it.seq }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 批次按 createdAt 倒序；组内任务按 seq 升序
            orderedBatches.forEach { batch ->
                val batchTasks = grouped[batch.id].orEmpty().sortedBy { it.seq }
                // 任务下载成功后记录会被删除，列表里没有 COMPLETED 任务，
                // 故按 batch.total - 该批次剩余任务数估算完成数（remaining 严格按 batchId 过滤）
                val doneCount = completedCount(batch.total, tasks.count { it.batchId == batch.id })
                item(key = "batch-${batch.id}") {
                    BatchHeader(
                        batch = batch,
                        doneCount = doneCount,
                        onPause = { onPauseBatch(batch.id) },
                        onResume = { onResumeBatch(batch.id) },
                        onRetry = { onRetryBatch(batch.id) },
                        onOpenFolder = { onOpenBatchFolder(batch) },
                        onRemove = { onRemoveBatch(batch.id) },
                    )
                }
                items(batchTasks, key = { it.id }) { task ->
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
            // batchId == null 的旧版本任务兼容区
            if (ungrouped.isNotEmpty()) {
                item(key = "ungrouped-header") {
                    Text(
                        "未分组任务（旧版本）",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
                items(ungrouped, key = { it.id }) { task ->
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
}

@Composable
private fun BatchHeader(
    batch: DownloadBatch,
    doneCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onOpenFolder: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${batch.playlistTitle} · 完成 $doneCount/${batch.total}",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onPause) { Text("暂停批次") }
                OutlinedButton(onClick = onResume) { Text("继续批次") }
                OutlinedButton(onClick = onRetry) { Text("重试") }
                OutlinedButton(onClick = onOpenFolder) { Text("目录") }
                OutlinedButton(onClick = onRemove) { Text("删除") }
            }
        }
        // 批次整体进度：与文字的 完成 $doneCount/${batch.total} 用同一口径
        LinearProgressIndicator(
            progress = if (batch.total > 0) doneCount.toFloat() / batch.total else 0f,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
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

private fun speedLimitLabel(kbps: Int): String = when (kbps) {
    0 -> "不限"
    512 -> "512KB/s"
    1024 -> "1MB/s"
    2048 -> "2MB/s"
    else -> "$kbps KB/s"
}
