# NeriPlayer QQ 音乐下载实现与移植评估

> 文档版本：v0.1（2026-11-15）  
> 读者：QM-Download 后续开发者  
> 目的：回答「NeriPlayer 如何实现 QQ 音乐下载」以及「哪些部分值得移植到 QM-Download」。  
> 状态：**调研文档，未修改代码**；待确认后再进入实现。

---

## 0. 结论摘要

NeriPlayer 的 QQ 音乐下载链路可以概括为：

```
歌单/歌曲
  → AudioDownloadManager.downloadSong()
  → AudioDownloadSourceResolver.resolveQq()
  → QQMusicPlaybackRepository.resolve()
  → music.vkey.GetVkey / UrlGetVkey  →  CDN 直链
  → 工作文件（staging，可续传）
  → 传输校验（长度 / MD5 / 时长 / 音轨）
  → 提交到最终文件
  → 写入下载目录 / 目录数据库
  → 可选：封面、歌词等 sidecar
```

几个关键事实：

1. **QQ 返回的是明文直链**（FLAC/MP3），正常情况下不需要解密；`ekey` 为空。
2. **VKey 请求有身份要求**：NeriPlayer 用 `ct=11 / cv=14090008 / authst=musicKey`；
   `guid` 必须是稳定值（优先 `wid`，否则 `uin`），随机 `guid` 会得到 `104009 / invalidq`。
3. **音质是候选链**：无损 `F000` → 320k `M800` → 128k `M500`；
   某一档 `result != 0` 应视为“这一档不可用”，继续降级，而不是整体失败。
4. **下载后必须校验**：只判断 HTTP 200 不够；QQ CDN 对无权限曲目会返回
   HTTP 200 + 只有 FLAC 元数据、没有音频帧的 8KB 占位文件。
5. NeriPlayer 的下载器非常完整，但深度绑定 Android（Room、WorkManager、前台服务、
   MediaMetadataRetriever、SAF）；QM-Download 不应整套照搬，应移植其**协议与校验思想**。

---

## 1. 调研范围与证据

### 1.1 NeriPlayer 代码

| 文件 | 作用 |
|---|---|
| [`QQMusicPlaybackRepository.kt`](../../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/playback/QQMusicPlaybackRepository.kt) | VKey 请求、音质候选、URL 拼接 |
| [`QQMusicQualityStore.kt`](../../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/playback/QQMusicQualityStore.kt) | QQ 播放音质持久化 |
| [`QQMusicSessionStore.kt`](../../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/auth/QQMusicSessionStore.kt) | Cookie → Session、`g_tk` |
| [`AudioDownloadSourceResolver.kt`](../../NeriPlayer/modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/player/download/source/AudioDownloadSourceResolver.kt) | 各音源下载地址解析（QQ 在 `resolveQq`） |
| [`AudioDownloadManager.kt`](../../NeriPlayer/modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt) | 下载入口、队列、取消、批量 |
| [`AudioDownloadManagerAttempt.kt`](../../NeriPlayer/modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/player/download/runtime/AudioDownloadManagerAttempt.kt) | 单次下载尝试、staging、提交 |
| [`AudioDownloadFileTransfer.kt`](../../NeriPlayer/modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/player/download/transfer/AudioDownloadFileTransfer.kt) | 直链/Range/分块传输、续传 |
| [`AudioDownloadManagerAssets.kt`](../../NeriPlayer/modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/player/download/sidecar/AudioDownloadManagerAssets.kt) | 下载后完整性校验、封面/歌词 sidecar |
| [`ManagedDownloadNaming.kt`](../../NeriPlayer/modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/download/naming/ManagedDownloadNaming.kt) | 文件命名模板、清洗、长度限制 |
| [`DownloadAudioQualitySettings.kt`](../../NeriPlayer/modules/local/src/main/java/moe/ouom/neriplayer/data/settings/download/DownloadAudioQualitySettings.kt) | 下载音质设置与归一化 |
| [`README.qqmusic.md`](../../NeriPlayer/README.qqmusic.md) | 功能与限制说明 |

### 1.2 探针与实测记录

| 文件 | 作用 |
|---|---|
| [`tools/qq-probe/README.md`](../../tools/qq-probe/README.md) | 探针方法、身份矩阵、失败判读 |
| [`tools/qq-probe/FINDINGS.md`](../../tools/qq-probe/FINDINGS.md) | 无损直链、明文 FLAC、`size_flac=0` 的实测 |
| [`tools/qq-probe/download_test.py`](../../tools/qq-probe/download_test.py) | VKey → HEAD → 魔术字节 → 下载 → SHA256 的最小闭环 |
| [`tools/qq-probe/VERIFIED_CAPABILITIES.md`](../../tools/qq-probe/VERIFIED_CAPABILITIES.md) | 能力复核与写权限边界 |

### 1.3 QM-Download 当前实现

| 文件 | 作用 |
|---|---|
| [`core/.../QQMusicUrlResolver.kt`](../core/src/main/kotlin/moe/ouom/qmdownload/core/playback/QQMusicUrlResolver.kt) | 当前 VKey 解析 |
| [`core/.../QQMusicDownloader.kt`](../core/src/main/kotlin/moe/ouom/qmdownload/core/download/QQMusicDownloader.kt) | 当前流式下载 |
| [`core/.../AudioFileValidator.kt`](../core/src/main/kotlin/moe/ouom/qmdownload/core/download/AudioFileValidator.kt) | 占位文件校验 |
| [`core/.../AudioTagWriter.kt`](../core/src/main/kotlin/moe/ouom/qmdownload/core/download/AudioTagWriter.kt) | MP3/FLAC 标签写入 |
| [`core/.../DownloadFileNames.kt`](../core/src/main/kotlin/moe/ouom/qmdownload/core/download/DownloadFileNames.kt) | 官方风格命名 |
| [`desktopApp/.../Main.kt`](../desktopApp/src/main/kotlin/moe/ouom/qmdownload/desktop/Main.kt) | Desktop 下载 UI |
| [`androidApp/.../AndroidDownloadManager.kt`](../androidApp/src/main/java/moe/ouom/qmdownload/android/AndroidDownloadManager.kt) | Android SAF 下载 |

---

## 2. 整体下载链路（NeriPlayer）

```
┌──────────────┐
│ 歌单/歌曲 UI  │
└──────┬───────┘
       │ downloadSong(song, quality)
       ▼
┌─────────────────────────────┐
│ AudioDownloadManager        │
│  - 任务/批量/并发/取消       │
│  - 失败重试/网络策略         │
└──────┬──────────────────────┘
       │ resolveDownloadSourceForAttempt
       ▼
┌─────────────────────────────┐
│ AudioDownloadSourceResolver │
│  QQ → resolveQq()           │
└──────┬──────────────────────┘
       │ song.audioId (songMid)
       │ song.subAudioId (media_mid)
       ▼
┌─────────────────────────────┐
│ QQMusicPlaybackRepository   │
│  - 候选链 F000/M800/M500     │
│  - VKey 请求                 │
│  - purl + sip → URL          │
└──────┬──────────────────────┘
       │ ResolvedDownloadSource
       ▼
┌─────────────────────────────┐
│ AudioDownloadManagerAttempt │
│  - staging 工作文件          │
│  - 断点/续传元数据           │
│  - transfer + 校验 + commit  │
└──────┬──────────────────────┘
       ▼
┌─────────────────────────────┐
│ 最终文件 + 目录数据库        │
│ 可选：封面/歌词 sidecar      │
└─────────────────────────────┘
```

---

## 3. 登录态与请求身份

### 3.1 Session 字段

NeriPlayer 的 `QQMusicSession`：

```kotlin
data class QQMusicSession(
    val cookie: String,
    val uin: String,
    val musicKey: String,   // qm_keyst
    val authst: String = "",
    val musicId: String = "",
    val sid: String = "",
    val wid: String = "",
)
```

- Cookie 别名优先级：
  - uin：`qqmusic_uin → uin → p_uin`
  - key：`qm_keyst → qqmusic_key → music_key`
- `g_tk = qqMusicHash33(musicKey)`（seed 5381，低 31 位）。
- Web 扫码通常只有 `uin + qm_keyst`；
  `authst/sid/wid` 需要 App/WNS 登录，普通扫码拿不到。

### 3.2 VKey 使用的身份

NeriPlayer 的 `requestVkeyUrl()` 使用：

```json
"comm": {
  "ct": 11,
  "cv": 14090008,
  "v": 14090008,
  "qq": "<uin>",
  "authst": "<musicKey>",
  "tmeAppID": "qqmusic",
  "format": "json"
}
```

也就是说：**把 Web 的 `qm_keyst` 当作 `authst` 用**，配合 Android 客户端身份请求 VKey。
实测这条路径可以拿到无损直链（账号有权益时）。

> 注意：`authst` 在这里是“播放令牌”的复用，不等于 App 写接口所需的 WNS `authst`。
> 不要把 VKey 的 `authst=musicKey` 与歌单写接口的 App `authst` 混为一谈。

---

## 4. VKey 解析（最核心）

### 4.1 请求参数

```json
{
  "comm": { "ct": 11, "cv": 14090008, "qq": "<uin>", "authst": "<musicKey>", ... },
  "req_0": {
    "module": "music.vkey.GetVkey",
    "method": "UrlGetVkey",
    "param": {
      "uin": "<uin>",
      "guid": "<wid 或 uin>",
      "filename": ["F000<media_mid>.flac"],
      "vecPrefix": ["F000"],
      "downloadfrom": 0,
      "source": "search",
      "songmid": ["<songMid>"],
      "songtype": [0],
      "ctx": 0
    }
  }
}
```

要点：

| 字段 | 说明 |
|---|---|
| `guid` | **必须稳定**；随机值会触发 `104009 / invalidq`。优先 `wid`，否则 `uin` |
| `filename` | 文件码 + **media_mid** + 扩展名；缺 `media_mid` 时会降级 |
| `vecPrefix` | 与 filename 前缀一致 |
| `downloadfrom` | NeriPlayer 用 0；QM-Download 当前用 1，需实测确认 |
| `source` | NeriPlayer 用 `search` |
| `songtype` | 通常 0（单曲） |

### 4.2 文件名规则

`QQQualityCandidate.fileName()`：

```
有 media_mid：F000 + media_mid + .flac
无 media_mid：F000 + songMid + songMid + .flac
```

`media_mid` 来自歌单/详情响应的 `file.media_mid`，与 `songmid` 常常不同。
缺它会导致高音质解析失败，只能退回标准档。

### 4.3 音质候选链

```kotlin
lossless = F000 / .flac
exhigh   = M800 / .mp3 / 320k
standard = M500 / .mp3 / 128k
```

- 请求“无损”：`[F000, M800, M500]`
- 请求“320k”：`[M800, M500]`
- 其他：`[M500]`

**关键行为**：某一档 `result != 0` 或 `purl` 为空时，应返回 null/继续下一档，
而不是抛异常终止。NeriPlayer 正是这样处理的（见 4.4）。

### 4.4 结果判读

`requestVkeyUrl()`：

1. HTTP 非 200 → 抛 IO 错误；
2. `req_0.code != 0` → 抛错（身份/接口问题）；
3. `data.midurlinfo[0].result != 0` → **return null，继续降级**；
4. `purl` 为空 → return null；
5. `purl` 是绝对 URL → 直接 HTTPS 化；
6. 否则用 `sip` 域名拼接，强制 HTTPS；
7. 返回绝对 URL。

`resolve()` 外层：

- 如果所有候选都失败：
  - 未登录 → `LoginRequired`
  - 已登录 → `Unavailable(lastReason)`
- 如果拿到 → `Playable(url, actualQuality, bitrate, format)`。

### 4.5 常见错误码/现象

| 现象 | 含义 | 处理 |
|---|---|---|
| `req_0.code = 1000` | Cookie/身份失效或被拒 | 重新登录 |
| `req_0.code = 2001` | 网关判定匿名/未登录 | 重新登录 |
| `result = 104003` | 无该档位权限/无版权 | 继续降级或提示无权限 |
| `result = 104009` + `invalidq` | `guid` 非法（常见于随机 guid） | 用稳定 `wid/uin` |
| `purl` 为空但 code=0 | 该档位无资源 | 继续降级 |
| `ekey` 非空 | 加密文件 | 当前项目不支持，停止 |
| `sip` 是 `http://` | CDN 返回明文协议 | 强制升级 HTTPS |

### 4.6 探针的差异

`tools/qq-probe/probe.py` 的 `get_vkey()` 使用：

```
filename = prefix + songmid + media_mid + ext
```

而 NeriPlayer 的实现是：

```
filename = prefix + media_mid + ext
```

两者不完全一致。**不要把探针的 filename 直接抄进产品代码**；
以 NeriPlayer 已验证的规则为准，并保留降级路径。

---

## 5. 下载执行（NeriPlayer）

### 5.1 入口

`AudioDownloadManager.downloadSong()` / `downloadPlaylist()`：

- 单曲/批量；
- 持久化任务（Room）与恢复；
- 并发上限、取消、网络策略暂停；
- 前台服务/Worker（Android）。

### 5.2 来源解析

`AudioDownloadSourceResolver.resolveQq()`：

```kotlin
val songMid = song.audioId ?: song.id.toString()
val result = qqMusicPlaybackRepository.resolve(
    songMid = songMid,
    mediaMid = song.subAudioId,
    qualityKey = preferredQuality,
)
```

映射为：

```kotlin
ResolvedDownloadSource(
    url = result.url,
    mimeType = "audio/flac" 或 "audio/mpeg",
    fileExtensionHint = "flac" / "mp3",
    contentLength = null,
    durationMs = song.durationMs,
)
```

QQ 的 `contentLength` 不预填，由 HTTP 响应或后续校验确定。

### 5.3 工作文件与断点

`AudioDownloadManagerAttempt`：

- 用 `ManagedDownloadStorage.createWorkingFile()` 创建 staging 工作文件；
- 保存 resume metadata（ETag/Last-Modified/已下载长度）；
- 失败/暂停保留工作文件，重启后可续传；
- 提交前与 durable checkpoint 对齐，必要时截断到安全长度。

### 5.4 传输

`AudioDownloadFileTransfer`：

- 直链下载；
- HTTP Range 断点续传；
- 分块 Range（YouTube 等）；
- `If-Range` 校验、Content-Range 校验；
- 传输 watchdog、取消、重试；
- 最终字节数 / Content-Length 校验。

QQ 走普通直链，主要使用 Range 续传。

### 5.5 完整性校验（重点）

`verifyDownloadedAudioPayload()` 在提交前执行：

1. 文件非空；
2. 文件长度与传输计数/期望长度一致；
3. 若来源有 MD5 → 校验 MD5；
4. `MediaMetadataRetriever` 检查是否存在音轨（`METADATA_KEY_HAS_AUDIO != no`）；
5. 用 TagLib/MediaMetadataRetriever 读取时长；
6. 与歌曲/来源时长比较；
7. 校验期间文件大小不能变化。

这正是 QM-Download 目前最欠缺的部分：
当前只检查 FLAC 是否有音频帧 / MP3 是否有帧同步 / 最小 64KB，
没有校验**时长**、**来源长度**、**音轨可读性**。

### 5.6 提交与 sidecar

- 校验通过后提交到最终文件（目录数据库/Room）；
- 异步下载封面、歌词等 sidecar；
- 封面/歌词失败不删除音频，只标记降级；
- 有完整的失败恢复与清理机制。

---

## 6. 文件命名与标签

### 6.1 NeriPlayer 命名

`ManagedDownloadNaming.kt`：

- 默认模板：

```
%title% - %artist% - %album% - %source%
```

- 旧模板：`%artist% - %title%` 等；
- 清洗非法字符 `\/:*?"<>|`；
- 文件名最多 **128 UTF-8 字节**，扩展名单独限制；
- 支持用户自定义模板（`%title%/%artist%/%album%/%source%/%id%...`）。

> 这与 QQ 官方客户端的“歌手-歌曲名”并不一致；
> QM-Download 为了官方识别，选择了 `%artist%-%title%`，这是有意的差异。

### 6.2 标签

在 NeriPlayer 下载相关代码中，**未发现写入 ID3/Vorbis 标签的调用**；
它主要依赖：

- 文件名；
- 下载目录数据库/媒体库元数据；
- 封面/歌词 sidecar。

QM-Download 当前会写入：

- MP3：ID3v2.3 TIT2/TPE1/TALB；
- FLAC：Vorbis Comment TITLE/ARTIST/ALBUM。

这是 QM-Download 为“官方客户端识别”增加的实现，移植时应保留。

---

## 7. 探针给出的能力边界

| 结论 | 证据 |
|---|---|
| 无损可拿到明文 FLAC 直链 | `FINDINGS.md`：HEAD 长度与 `size_flac` 一致，前 4 字节 `fLaC` |
| 无 `ekey`，无需解密 | 同上 |
| `size_flac=0` 表示曲目本身无无损，不是账号无权限 | 霸王卸甲/十面埋伏实测 |
| `sip` 可能是 HTTP | 需 `secureUrl()` |
| 直链有时效 | 解析后立即下载 |
| Web/Android 只读接口无差异 | 4 端点 × 2 身份矩阵 |
| 付费/VIP 曲目可能无 purl | `result=104003` |
| 无权限时 CDN 可能返回 8KB 占位 FLAC | QM-Download 实测：只有元数据、0 音频帧 |

---

## 8. QM-Download 当前实现对照

| 能力 | NeriPlayer | QM-Download 当前 | 差距/建议 |
|---|---|---|---|
| VKey 身份 | `ct=11 + authst=musicKey` | `ct=24 + g_tk` | **建议对齐 NeriPlayer 并实测** |
| 稳定 guid | `wid → uin` | `uin` | QM 可用，但缺 `wid` 支持 |
| 候选降级 | `result != 0` 继续 | **抛 NoPermission 直接终止** | **P0 修复**：改为继续下一档 |
| 直链 HTTPS | 有 | 有 | 一致 |
| 下载传输 | 直链 + Range + 分块 + 重试 | 单次流式 | P1 移植 Range/重试 |
| 断点续传 | 有 | 无 | P1 |
| 队列/并发 | 有（批量、并发、取消） | 串行 UI 协程 | P1 |
| Android 后台 | 前台服务/Worker | Activity 协程 | P1 |
| 下载后校验 | 长度/MD5/时长/音轨 | 文件头/最小 64KB | P0 增强 |
| 重复下载 | 目录/数据库判定 | 文件名判定 | P1 用内容/大小/目录索引 |
| 文件命名 | 模板 + 128 字节限制 | 官方 `歌手-歌名` | 保留官方风格，移植长度限制 |
| 标签 | 未见写入 | MP3/FLAC 手写 | 保留 QM 优势 |
| 封面/歌词 | sidecar | 无 | P2 |
| 分享链接 | 无（需另做） | 解析器已有，未接 UI | P2 |
| 错误模型 | 类型化 | `QmError` 基础类型 | 增加 `NoResource` 使用 |

### 8.1 已定位的 QM 代码问题

1. **降级被中断**：`QQMusicUrlResolver` 在 `result != 0` 时直接抛
   `NoPermission`，不会继续 `M800/M500`。对于“无损不可用但 320k 可用”的曲目，
   这是功能性 bug。
2. **身份差异**：QM 用 `ct=24`，Neri 用 `ct=11 + authst=musicKey`；
   前者对某些曲目可能拿不到 purl，建议 A/B 实测后统一。
3. **校验弱**：只判断 FLAC 有音频帧/MP3 有帧同步，不校验时长；
   对“有帧但极短”的占位文件仍可能漏过。
4. **重复下载判定**：只用文件名，无法识别同名不同内容/已存在但损坏的文件。
5. **无取消/暂停/断点**：Android 退后台可能中断。

---

## 9. 移植方案（建议）

### P0：先修正确性（1–2 天）

1. **VKey 身份对齐**
   - 增加 `ct=11/cv=14090008/authst=musicKey/qq=uin` 请求；
   - 保留 `ct=24` 作为回退；
   - 用同一首歌对比两者 purl/result，记录结论。
2. **候选链不中断**
   - `result != 0` / `purl` 空 → 继续下一档；
   - 只有 1000/2001 等身份错误才终止并提示重新登录。
3. **下载后校验增强**
   - 至少校验：文件 > 64KB、时长 > 0、时长与歌曲时长偏差在阈值内；
   - Android 可用 `MediaMetadataRetriever`/`MediaExtractor`；
   - Desktop 可用 TagLib/jaudiotagger 或轻量 MP3/FLAC 头解析。
4. **重复下载判定**
   - 文件存在且校验有效才跳过；
   - 记录已下载的 songMid + 质量 + 文件大小，避免同名误判。

### P1：下载队列与后台（3–5 天）

1. 引入 `DownloadTask`/`DownloadQueue` 状态机：
   `Queued → Resolving → Downloading → Completed/Failed/Canceled`；
2. `.part` 工作文件 + Range 续传 + 指数退避；
3. Android：
   - 前台服务（`dataSync`）+ 通知；
   - 应用退后台继续下载；
   - 失败重试/取消；
4. Desktop：
   - 协程队列 + 并发上限；
   - 取消/重试按钮；
5. 统一进度/速度/剩余时间。

### P2：体验与完整性（3–5 天）

1. 封面/歌词 sidecar（可选）；
2. 文件命名模板（官方 `歌手-歌名` 为默认，可配置）；
3. UTF-8 字节长度限制（替换当前 120 字符限制）；
4. 分享链接输入框；
5. Miuix/NeriPlayer 风格 UI；
6. 已下载列表/重复检测。

### 不建议直接移植

| 部分 | 原因 |
|---|---|
| 1800 行 `AudioDownloadManager` + Room + operation journal | 对 QM-Download 过重，维护成本高 |
| Android WorkManager/前台服务代码直接搬到 Desktop | 平台不兼容 |
| NeriPlayer 默认命名模板 | 与“QQ 官方识别”目标冲突 |
| 完整媒体库/播放器/歌词体系 | 超出下载器范围 |

---

## 10. 推荐的目标架构（QM-Download）

```
core/
  auth/         session + QR
  library/      playlists + tracks
  playback/     VKey resolver（候选链、身份回退）
  download/
    DownloadTask.kt        状态机
    DownloadQueue.kt       并发/取消/重试
    RangeDownloader.kt     .part + Range
    AudioValidator.kt      大小/时长/音轨
    FileNames.kt           官方命名 + UTF-8 限制
    TagWriter.kt           ID3/Vorbis
androidApp/
  ForegroundDownloadService + 通知 + SAF
desktopApp/
  DownloadQueue + JFileChooser + 进度面板
```

---

## 11. 风险与合规

- 仅下载账号本来有权访问的内容；不绕过会员/付费/DRM。
- QQ 接口/直链会变；VKey 需要稳定 `guid`、有效 Cookie、及时下载。
- 付费/VIP 曲目无权限时，应明确提示，而不是生成占位文件。
- 项目衍生自 GPL-3.0 的 NeriPlayer，QM-Download 必须继续 GPL-3.0 并保留版权头。
- 不要提交/打印 Cookie、`qm_keyst`、`authst`。

---

## 12. 待确认

1. 是否同意 P0 先做：VKey 身份对齐、候选链修复、校验增强、重复判定？
2. 音质回退策略：用户选“无损”时，无损不可用是否自动降 320k/128k？
3. 文件命名：继续固定 `歌手-歌曲名`，还是提供模板但默认官方风格？
4. 标签：是否继续手写 MP3/FLAC 标签（当前方案）？
5. P1 是否接受 Android 前台服务 + 通知的复杂度？

---

## 附录 A：关键 API 速查

| 用途 | module / method |
|---|---|
| 我的歌单 | `music.musicasset.PlaylistBaseRead / GetPlaylistByUin` |
| 歌单详情 | `music.srfDissInfo.DissInfo / CgiGetDiss` |
| 歌曲详情/media_mid | `music.pf_song_detail_svr / get_song_detail_yqq` |
| VKey | `music.vkey.GetVkey / UrlGetVkey` |
| 账号资料 | `c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg` |

## 附录 B：推荐阅读顺序

1. 本文第 3–5 节（协议与下载链路）
2. NeriPlayer `QQMusicPlaybackRepository.kt`
3. NeriPlayer `AudioDownloadSourceResolver.kt` 的 `resolveQq`
4. NeriPlayer `AudioDownloadManagerAttempt.kt` 的 `transferAndCommitDownloadAttempt`
5. NeriPlayer `AudioDownloadManagerAssets.kt` 的 `verifyDownloadedAudioPayload`
6. `tools/qq-probe/FINDINGS.md` 与 `download_test.py`

---

> 本文只做现状与技术分析，未修改 QM-Download 代码。确认移植范围后再进入实现。
