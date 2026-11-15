# QM-Download 下载器与分享链接优化方案

> 版本：v0.2（2026-11-15）  
> 状态：**S1、独立下载页、并行任务 1–6、单任务暂停/恢复、失败自动重试 3 次已完成**；限速/仅 Wi-Fi/速度显示待继续。  
> 参考对象：aria2、Motrix、Ketch、bdownloader、NeriPlayer，以及当前 QM-Download 实现。

---

## 0. 结论摘要

QM-Download 现在已经能下载，但仍是一个“能跑”的下载器：

- 单连接流式下载；
- `.part` + Range 续传（简化版）；
- 串行队列；
- Android 前台服务、Desktop 取消；
- 没有暂停/恢复、速度限制、多连接、任务持久化面板；
- 分享链接解析器已有，但没有输入入口。

建议不要直接嵌入 aria2 或照搬 NeriPlayer 的 1800 行管理器，而是：

> **保留 Kotlin 原生实现，吸收 aria2 的“分段/续传/校验”思想、Ketch 的“任务生命周期”思想、NeriPlayer 的“来源解析 + 强校验”思想。**

分三阶段：

1. **S1 正确性**：任务持久化、暂停/恢复、分享链接入口；
2. **S2 性能**：大文件 2–4 连接分段下载、失败重试、并发队列；
3. **S3 体验**：速度限制、仅 Wi-Fi、通知操作、历史记录、命名模板。

---

## 1. 当前实现盘点

| 能力 | 当前状态 |
|---|---|
| VKey 双身份 | ✅ `ct=11 + authst` 优先，Web 回退 |
| 音质降级 | ✅ F000 → M800 → M500 |
| 下载 | 单连接 HTTP 流式 |
| 续传 | `.part` + `Range`，简单实现 |
| 重试 | 3 次，固定间隔 |
| 并发 | 串行（Android 服务/Desktop 队列） |
| 暂停/恢复 | ❌ |
| 取消 | ✅ Desktop/Android 通知 |
| 校验 | 大小、FLAC 帧/时长、MP3 帧同步 |
| 标签 | MP3/FLAC 手写（已修复音频帧丢失） |
| 分享链接解析 | ✅ 解析器 |
| 分享链接 UI | ❌ |
| 任务持久化 | ❌（重启后任务丢失） |
| 速度限制/仅 Wi-Fi | ❌ |
| 封面/歌词 | ❌ |

---

## 2. 参考项目对比

| 项目 | 可借鉴 | 不适合直接搬 |
|---|---|---|
| [aria2](https://github.com/aria2/aria2) / [文档](https://aria2.github.io/manual/en/html/aria2c.html) | 多连接分段、`--continue`、`--split`、`--max-connection-per-server`、校验、重试、RPC 任务模型 | C++ 守护进程；Android 集成/体积/维护成本高 |
| [Motrix](https://github.com/agalwood/Motrix) | 桌面任务队列、暂停/恢复、速度/并发设置、RPC 管理 | Electron + aria2，体积大，不适合 QM-Download |
| [Ketch](https://github.com/khushpanchal/Ketch) | Android WorkManager 任务、暂停/恢复、状态回调 | 通用文件下载，缺少 QQ 音乐 VKey/音质逻辑 |
| [bdownloader](https://github.com/iam-eurika/bdownloader) | Android 多连接分段（Content-Length 可用时） | 较简单，缺少任务持久化/校验 |
| NeriPlayer（本地） | VKey、staging、Range、强校验、sidecar、错误分类 | Room/operation journal/前台 Worker 过重 |
| Aria2Android/Aria2App | Android 上跑 aria2 守护进程 + 前端 | 部署复杂、后台存活/权限问题多 |

### 结论

- **协议层**：继续用 Kotlin + OkHttp，不引入 aria2。
- **下载层**：吸收 aria2 的分段与续传参数，但限制连接数（QQ CDN 风控）。
- **任务层**：吸收 Ketch/aria2 的“任务状态 + 持久化 + 暂停/恢复”。
- **校验层**：沿用 NeriPlayer 的思路，补齐时长/大小/音频帧。
- **UI 层**：Motrix 的任务列表/队列交互可参考，但不做 Electron。

---

## 3. 优化建议（按优先级）

### S1：正确性与可用性（优先）

#### 3.1 任务持久化

当前下载任务只在内存；应用重启/服务被杀后丢失。

建议新增 `DownloadTaskStore`：

```kotlin
data class StoredDownloadTask(
    val id: String,
    val songMid: String,
    val mediaMid: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val quality: String,
    val targetUri: String,      // SAF URI 或桌面绝对路径
    val workingPath: String,    // .part 路径
    val totalBytes: Long?,
    val etag: String?,
    val lastModified: String?,
    val downloadedBytes: Long,
    val status: String,         // QUEUED/RUNNING/PAUSED/FAILED/COMPLETED
    val error: String?,
    val createdAt: Long,
)
```

- Android：Room 或 JSON（MVP 可先用 JSON，后续迁 Room）；
- Desktop：`data/tasks.json` 或 SQLite；
- 启动时恢复 `QUEUED/PAUSED/RUNNING`；
- 任务完成/失败保留历史，可清理。

#### 3.2 暂停/恢复

状态机：

```text
QUEUED → RESOLVING → DOWNLOADING → COMPLETED
   ↑          ↓           ↓
   └──── PAUSED ←─────────┘
              ↓
            FAILED → QUEUED（重试）
```

- 暂停：取消当前协程，保留 `.part` 与任务记录；
- 恢复：从 `part.length()` 继续 Range；
- 取消：删除 `.part` 与任务记录（或保留“已取消”历史）；
- 网络切换/断网：自动转 `PAUSED`，恢复网络后重试。

#### 3.3 分享链接入口

已有 `QQMusicShareLinkParser`，需要 UI：

- Android/桌面歌单页顶部或“+”菜单：
  - 输入框；
  - “粘贴”按钮（剪贴板）；
  - “打开”按钮。
- 流程：

```text
粘贴链接
  → parse() 取 playlistId
  → CgiGetDiss(disstid)
  → 展示歌单
  → 可全选/下载
```

- 错误状态：
  - 非 QQ 音乐链接；
  - 歌单不存在；
  - 需要登录/无权限；
  - 空歌单。
- 支持格式：
  - `https://y.qq.com/n/ryqq_v2/playlist/{id}`
  - `https://y.qq.com/n/ryqq/playlist/{id}`
  - 纯数字 ID

#### 3.4 错误分类

当前 `QmError` 还不够细，建议：

```text
AuthExpired        → 重新登录
NoPermission       → 付费/VIP/无版权
NoResource         → 该音质无资源
Network            → 可重试
Http5xx            → 可重试
Integrity          → 删除文件、重下
Cancelled          → 用户取消
```

UI 根据类型给不同按钮（重试/换音质/重新登录）。

---

### S2：性能与稳定性

#### 4.1 多连接分段下载（借鉴 aria2）

仅对**大文件**启用，避免小文件/风控问题：

- 阈值：`Content-Length >= 8 MB`；
- 连接数：Android 默认 2，Desktop 默认 3，最多 4；
- 单服务器连接上限：2–3；
- 分段：

```text
总长度 L，N 段：
segment i: [i*L/N, (i+1)*L/N - 1]
每个 segment 写 <target>.part.<i>
```

- 先请求 `Range: bytes=0-0` 或 HEAD 确认：
  - `Accept-Ranges: bytes`；
  - `Content-Length`；
  - `ETag/Last-Modified`。
- 每段独立 Range，校验 `Content-Range`；
- 全部完成后按顺序合并到 `.part`，再原子改名；
- 任一段失败：重试该段；多次失败 → 回退单连接；
- 服务端不支持 Range/返回 200 → 回退单连接。

> QQ CDN 可能对多连接敏感；建议默认关闭，提供“加速下载（实验）”开关，
> 并限制 2 连接。

#### 4.2 重试与退避

- 网络错误/5xx：指数退避 1s、2s、4s、8s，最多 4 次；
- 403/404：不重试，刷新 VKey 一次；
- 401/1000/2001：暂停队列，提示重新登录；
- 校验失败：删除文件，重新解析 VKey 后重下（最多 1 次）。

#### 4.3 并发队列

- Android：2 个并发任务；
- Desktop：3 个并发任务；
- 同一首歌/同一目标文件去重；
- 优先级：用户手动 > 批量；
- 队列可暂停/恢复/取消。

#### 4.4 速度限制与网络策略

- 全局速度上限（KB/s）；
- 仅 Wi-Fi 下载（Android `NetworkCapabilities`）；
- 低电量/省电模式暂停（可选）。

---

### S3：体验与完整性

#### 5.1 通知与前台服务

- 通知显示：任务名、进度、速度、剩余时间；
- 操作：暂停/恢复/取消；
- 多任务：合并通知或每个任务一条；
- Android 14 前台服务类型 `dataSync`。

#### 5.2 桌面任务面板

参考 Motrix：

- 左侧/底部任务列表；
- 状态：等待/下载中/暂停/完成/失败；
- 操作：暂停/恢复/重试/打开目录；
- 全局速度/并发设置。

#### 5.3 文件与元数据

- 命名模板：
  - 默认官方 `%artist%-%title%`；
  - 可选 `%title%-%artist%`、`%album%/%artist%-%title%`；
- UTF-8 字节长度限制（当前 120 字符，建议 128–200 字节）；
- 标签写入失败提示；
- 重复检测：songMid + 质量 + 文件大小/校验；
- 可选封面/歌词 sidecar（参考 NeriPlayer）。

#### 5.4 日志与诊断

- 任务 ID / 阶段 / HTTP 状态 / 字节数；
- 错误码分类；
- 日志脱敏（不打印 Cookie/authst/vkey）；
- 导出诊断包（日志 + 配置，不含凭据）。

---

## 4. 推荐架构

```
core/
  download/
    DownloadTask.kt          # 任务模型/状态机
    DownloadTaskStore.kt     # 持久化接口
    DownloadScheduler.kt     # 队列/并发/重试
    SegmentPlanner.kt        # 分段计划
    SegmentDownloader.kt     # 单段 Range
    SingleStreamDownloader.kt# 回退单连接
    DownloadValidator.kt     # 大小/时长/音频帧
    DownloadSink.kt          # File / SAF 写入
    DownloadFileNames.kt
    TagWriter.kt
androidApp/
  DownloadService.kt         # 前台服务 + 通知
  AndroidTaskStore.kt        # Room/JSON
desktopApp/
  DownloadQueue.kt           # 协程队列
  DesktopTaskStore.kt
```

### 关键接口草案

```kotlin
interface DownloadSink {
    suspend fun open(): OutputStream
    suspend fun commit()
    suspend fun abort()
}

interface DownloadTaskStore {
    suspend fun loadAll(): List<StoredDownloadTask>
    suspend fun save(task: StoredDownloadTask)
    suspend fun delete(id: String)
}

class DownloadScheduler(
    private val store: DownloadTaskStore,
    private val resolver: QQMusicUrlResolver,
    private val downloader: SegmentDownloader,
    private val maxConcurrent: Int,
) {
    fun enqueue(request: DownloadRequest): String
    fun pause(id: String)
    fun resume(id: String)
    fun cancel(id: String)
}
```

---

## 5. 分享链接功能方案

### 5.1 入口

- Android：媒体库/歌单页顶部“粘贴链接”；
- Desktop：左侧歌单栏顶部输入框 + “打开”。

### 5.2 交互

```
[粘贴] [输入框................] [打开]
```

- 自动识别剪贴板中的 QQ 音乐链接；
- 打开后显示歌单封面/标题/歌曲数；
- 可直接“全选下载”。

### 5.3 后端

已有：

- `QQMusicShareLinkParser.parse()`
- `QQMusicLibraryRepository.playlistTracksPage()`

需要补：

- `loadPlaylistMeta(playlistId)`：从 `CgiGetDiss` 返回 `dissname`/`logo`/`songnum`；
- 分享歌单可能没有 `dirId`，只读即可；
- 错误映射：不存在/无权限/空歌单。

### 5.4 验收

- 示例链接：

```text
https://y.qq.com/n/ryqq_v2/playlist/9735321048?ADTAG=h5_share_playlist...
```

- 打开后显示 39 首（以实际为准）；
- 可全选/下载；
- 非法链接有明确提示。

---

## 6. 实施阶段建议

| 阶段 | 内容 | 预计 |
|---|---|---|
| S1 | 任务持久化、暂停/恢复、分享链接入口、错误分类 | 3–5 天 |
| S2 | 大文件多连接、重试退避、并发队列、速度/网络策略 | 4–6 天 |
| S3 | 通知操作、桌面任务面板、命名模板、sidecar、诊断包 | 4–6 天 |

### 验收标准

- 杀进程/重启后任务可恢复；
- 暂停后恢复不重下已完成部分；
- 大文件 2 连接下载速度有提升且不触发风控；
- 分享链接可打开并下载；
- 失败原因可区分（权限/网络/校验）；
- 日志可定位问题且不泄露凭据。

---

## 7. 风险与取舍

| 风险 | 说明 | 建议 |
|---|---|---|
| 多连接触发风控 | QQ CDN 对并发敏感 | 默认关闭，2 连接上限，实验开关 |
| VKey 过期 | 直链短时有效 | 每段失败先刷新 VKey，再重试 |
| 任务持久化复杂 | Room/迁移成本 | 先用 JSON，稳定后迁 Room |
| 后台存活 | Android 厂商限制 | 前台服务 + WorkManager 兜底 |
| 分享歌单权限 | 公开/私密差异 | 明确错误提示，不静默空列表 |
| aria2 集成 | 体积/守护进程/权限 | 不嵌入，只借鉴算法 |

---

## 8. 建议结论

> **先做 S1，不要一上来做多连接。**

原因：

- 当前最大问题是“任务丢失/不能暂停/分享链接没入口”，不是速度；
- 多连接会增加 QQ 风控和实现复杂度；
- S1 完成后，下载器已经达到“可日常使用”的完整度；
- S2 再作为实验功能逐步开启。

确认后我按 S1 开始编码，并更新 `docs/DEVLOG.md` 与验收记录。

---

## 9. 独立“下载”页（已实现）

参考 aria2/Motrix 的任务视图，不再把暂停/继续混在歌单页顶部。

### 页面结构

```text
[音乐库] [下载（N）]

下载任务                       [全部暂停/全部继续]
共 N 个任务；暂停保留 .part
────────────────────────────────────────
歌曲名
歌手 · 无损 · 下载中              [暂停] [目录] [删除]
████████████░░░░  65%  6.2MB / 9.5MB
/下载目录

歌曲名 2
歌手 · 320k · 已暂停              [继续] [目录] [删除]
...
```

### 状态

- `QUEUED`：等待；
- `DOWNLOADING`：下载中，显示进度；
- `PAUSED`：暂停，显示已下载字节；
- `FAILED`：显示错误，可重试；
- `COMPLETED`：任务完成后从队列移除（文件保留）。

### 交互

- 顶部“暂停/继续”控制整个队列；
- 单任务：暂停/继续/重试/目录/删除；
- 删除只清理任务与 `.part`，不删除已完成的最终音频；
- 主界面只保留“下载所选”，提交后跳转到下载页。

### 平台

- Desktop：独立 Compose 页面 `DesktopDownloadsScreen`；
- Android：独立 Compose 页面 `AndroidDownloadsScreen`，任务来自 `FileDownloadTaskStore`，服务负责实际下载。

### 后续

- 下载页显示实时速度/剩余时间；
- 单任务暂停需要服务支持“按任务取消”（当前 Android 主要是整队列暂停）；
- 失败任务自动重试入口；
- 下载历史/已完成列表。
