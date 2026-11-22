# QM-Download 技术方案与详细设计

> 文档版本：v0.1（2026-11-15）  
> 前置文档：[`REPORT.md`](REPORT.md)  
> 状态：**设计阶段，未开始编码**。确认本方案后才进入实现。
> 目标读者：后续接手 QM-Download 的开发者、测试与维护者。

---

## 1. 范围与约束

### 1.1 本期范围

- QQ 音乐扫码登录；
- 账号资料（头像/昵称）；
- 账号歌单列表；
- 歌单详情、单选/全选歌曲；
- 下载队列、下载路径选择；
- 粘贴歌单分享链接解析并下载；
- Windows EXE + Android APK；
- 宽屏/窄屏自适应，视觉接近 NeriPlayer。

### 1.2 非目标

- 在线播放、歌词、评论、歌单编辑；
- 其他音源（网易云/B站/YouTube）；
- 绕过会员/付费/DRM；
- 账号多开、云同步、代理服务。

### 1.3 关键约束

| 约束 | 说明 |
|---|---|
| 登录态 | Web 扫码会话（`uin` + `qm_keyst/music_key`）；Cookie 会过期 |
| 音质 | 以账号实际权益为准；候选链降级 |
| VKey | `guid` 必须稳定；直链短时有效，下载要及时 |
| 许可 | 衍生自 GPL-3.0 的 NeriPlayer，QM-Download 必须 GPL-3.0 |
| 接口 | 非官方 API，可能变更；不做高频/批量滥用 |

---

## 2. 总体架构

采用 **Kotlin Multiplatform（KMP）+ Compose Multiplatform**：

```
┌─────────────────────────────────────────────────────┐
│                     core (KMP)                       │
│  auth / profile / playlist / link parser / vkey     │
│  download task & queue / models / error model       │
└───────────────┬─────────────────────┬───────────────┘
                │                     │
     ┌──────────▼─────────┐   ┌───────▼──────────────┐
     │    androidApp      │   │     desktopApp       │
     │ Compose + SAF      │   │ Compose Desktop      │
     │ Service/通知       │   │ JFileChooser/托盘    │
     └────────────────────┘   └──────────────────────┘
```

### 2.1 为什么不是直接复用 NeriPlayer app

- NeriPlayer `app` 是 Android-only，含大量 Activity/Service/通知/Media3/播放器代码；
- 直接依赖会把播放器、评论、歌词等无关模块全部带入；
- QM-Download 只保留「登录 + 歌单 + 下载」，需要**复制并重构**而不是依赖。

### 2.2 为什么用共享 core 而不是两套代码

- 登录、QQ API、VKey、歌单解析都是纯 Kotlin/OkHttp，可 100% 共享；
- Miuix 组件库已有 Desktop target，UI 风格可保持接近；
- 双端差异集中在「文件路径、持久化、通知/窗口、打包」。

> **实现说明（M0）**：为降低首版构建风险，`core` 当前是 **Kotlin/JVM 共享模块**，
> Android 与 Desktop 都依赖其 JVM 产物；后续如需 iOS，再增加 KMP target，
> 业务代码无需重写。

---

## 3. 仓库结构（建议）

```
QM-Download/
├── README.md
├── REPORT.md
├── DESIGN.md                  # 本文件
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
│
├── core/                      # KMP
│   └── src/
│       ├── commonMain/kotlin/moe/ouom/qmdownload/core/
│       │   ├── auth/
│       │   ├── profile/
│       │   ├── library/
│       │   ├── link/
│       │   ├── playback/
│       │   ├── download/
│       │   ├── model/
│       │   └── error/
│       ├── androidMain/
│       └── desktopMain/
│
├── androidApp/
│   └── src/main/kotlin/moe/ouom/qmdownload/android/
│       ├── QmDownloadApplication.kt
│       ├── MainActivity.kt
│       ├── ui/
│       ├── download/
│       └── storage/
│
├── desktopApp/
│   └── src/desktopMain/kotlin/moe/ouom/qmdownload/desktop/
│       ├── Main.kt
│       ├── ui/
│       └── storage/
│
└── ui/                        # 可选：共享 Compose 组件
    └── src/commonMain/kotlin/moe/ouom/qmdownload/ui/
```

> `ui` 模块可在 P1 后逐步抽取；初期允许 Android/Desktop 各自持有页面，但共享 **组件与主题**。

---

## 4. core 层设计

### 4.1 数据模型

```kotlin
data class QQMusicSession(
    val cookie: String,
    val uin: String,
    val musicKey: String,
) {
    val isLoggedIn: Boolean get() = uin.isNotBlank() && musicKey.isNotBlank()
}

data class QQMusicAccountProfile(
    val nickname: String,
    val avatarUrl: String?,
)

data class QQMusicPlaylist(
    val id: String,          // tid / dissid
    val dirId: Long,         // 写句柄；下载只读可为 0
    val title: String,
    val coverUrl: String?,
    val trackCount: Int,
    val isOwn: Boolean,
)

data class QQMusicTrack(
    val songMid: String,
    val mediaMid: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long,
)

data class PlaylistPage(
    val tracks: List<QQMusicTrack>,
    val total: Int,
)
```

### 4.2 错误模型

```kotlin
sealed class QmError(message: String) : Exception(message) {
    class AuthExpired : QmError("QQ 音乐登录已过期，请重新扫码")
    class Network(cause: Throwable) : QmError("网络请求失败")
    class Api(code: Int, msg: String?) : QmError("QQ 音乐接口错误：$code")
    class NoPermission : QmError("当前账号无权访问该内容")
    class NoResource(quality: String) : QmError("当前音质没有可用资源")
    class FileWrite(cause: Throwable) : QmError("文件写入失败")
    class Canceled : QmError("已取消")
}
```

规则：

- HTTP 200 但 `req_0.code == 1000/2001` → `AuthExpired`；
- `result == 104003/104009` → `NoPermission` / 重新登录；
- `purl` 为空 → `NoResource`，继续降级或提示；
- 所有 UI 只展示 `userMessage`，日志保留原始 code（**不打印 Cookie**）。

### 4.3 登录模块

```kotlin
interface QQMusicAuthService {
    suspend fun createQr(): QrSession
    suspend fun pollQr(session: QrSession): QrState
    suspend fun loadProfile(session: QQMusicSession): QQMusicAccountProfile?
}

sealed class QrState {
    data object Waiting : QrState()
    data object Scanned : QrState()
    data object Expired : QrState()
    data object Rejected : QrState()
    data class Authorized(val session: QQMusicSession) : QrState()
}
```

实现来源：

- 复制 [`QQMusicQrLoginClient.kt`](../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/auth/QQMusicQrLoginClient.kt)，去掉 Android `Context` 依赖；
- CookieJar 使用 OkHttp 内存实现；
- 二维码图片是服务端返回的 PNG/字节，直接显示；
- 登录成功回调保存 session 并关闭登录页。

### 4.4 会话存储（expect/actual）

```kotlin
interface SessionStore {
    suspend fun read(): QQMusicSession?
    suspend fun save(session: QQMusicSession)
    suspend fun clear()
}
```

| 平台 | 实现 |
|---|---|
| Android | `EncryptedSharedPreferences` 或 DataStore（不引入 Room） |
| Desktop | exe 同目录 `data/session.json`，文件权限仅当前用户 |

> 不把 Cookie 写入日志/崩溃报告；桌面端可后续接 Windows DPAPI。

### 4.5 账号资料

接口：`https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg`

参数：

```
g_tk, format=json, inCharset, outCharset, notice=0,
cid=205360838, needNewCode=0,
loginUin=<uin>, hostUin=0, userid=<uin>, reqfrom=1
```

解析：

```
data.creator.nick      → nickname
data.creator.headpic   → avatarUrl
```

失败返回 null，不阻断登录；UI 显示“QQ <uin>”。

### 4.6 歌单

```kotlin
interface QQMusicLibrary {
    suspend fun myPlaylists(session: QQMusicSession): List<QQMusicPlaylist>
    suspend fun playlistPage(
        session: QQMusicSession,
        playlistId: String,
        begin: Int,
        limit: Int,
    ): PlaylistPage
}
```

接口：

- 我的歌单：`music.musicasset.PlaylistBaseRead / GetPlaylistByUin`；
- 歌单详情：`music.srfDissInfo.DissInfo / CgiGetDiss`；
- 分页：`song_begin` / `song_num`，按服务端 `total` 推进；
- 去重：按 `songMid`，但保留序号用于展示。

### 4.7 分享链接解析

支持：

```
https://y.qq.com/n/ryqq_v2/playlist/9735321048?ADTAG=...
https://y.qq.com/n/ryqq/playlist/9735321048
https://y.qq.com/playlist/9735321048
```

解析规则：

1. 只接受 `*.qq.com` 域名；
2. 路径正则：`/playlist/(\d+)`；
3. 取第一个数字作为 `playlistId`；
4. 忽略 query（`ADTAG`、`redirecttag` 等）；
5. 解析失败 → `QmError.Api` 并提示“不是有效的 QQ 音乐歌单链接”。

打开流程：

```
粘贴链接 → 解析 id → CgiGetDiss(disstid=id) → 进入歌单页
```

若公开歌单返回空/无权限：

- 直接提示“歌单不存在或需要登录”；
- 不要静默变成空列表。

### 4.8 VKey / 下载地址

复用 `QQMusicPlaybackRepository`：

- 候选链：无损 `F000` → 320k `M800` → 128k `M500`；
- `guid`：优先 `wid`，否则 `uin`，**禁止随机**；
- 参数：`filename`、`vecPrefix`、`downloadfrom`、`source`、`songmid`、`songtype`、`ctx`；
- `purl` 为空/`result != 0`：降级；
- `ekey` 非空：当前不支持加密文件，报 `NoResource` 并提示；
- 直链是短时的：解析后立即下载，不落库。

---

## 5. 下载子系统

### 5.1 任务模型

```kotlin
data class DownloadRequest(
    val track: QQMusicTrack,
    val playlistTitle: String?,
    val quality: DownloadQuality,
)

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long?,
)

sealed class DownloadState {
    data object Queued : DownloadState()
    data object Resolving : DownloadState()
    data class Running(val progress: DownloadProgress) : DownloadState()
    data class Completed(val file: String) : DownloadState()
    data class Failed(val reason: String) : DownloadState()
    data object Canceled : DownloadState()
}
```

### 5.2 状态机

```
Queued
  → Resolving（VKey/权益）
    → Downloading（HTTP 流式）
      → Completed（原子重命名）
      → Failed（可重试）
    → Failed（无权限/无资源）
  → Canceled（任意阶段）
```

规则：

- 单任务失败不阻塞队列；
- 登录过期：暂停整个队列，提示重新登录，恢复后重试；
- 同一歌曲/同一路径去重；
- 已存在同名文件：默认跳过或加 `(1)`，可配置。

### 5.3 并发与断点

| 平台 | 实现 |
|---|---|
| Android | 前台 Service + 协程队列（默认 2 并发）；WorkManager 负责重启恢复 |
| Desktop | 协程 `Semaphore`（默认 3 并发） |

断点续传（P1）：

- 下载到 `xxx.part`；
- 记录已下载字节；
- 请求 `Range: bytes=<offset>-`；
- 服务端不支持 Range 时从头下载；
- 完成后原子 `rename` 为正式文件。

### 5.4 文件命名与目录（对齐 QQ 音乐官方）

**命名规则（P0）**

```
{artist}-{title}.{ext}
```

说明：

- QQ 音乐 PC 的“设置 → 下载与缓存 → 歌曲命名格式”提供 **“歌手-歌曲名”** 选项；
  本项目默认采用同样格式（连字符两侧**不加空格**）；
- 文件名只是辅助，QQ 音乐识别本地歌曲主要依赖 **音频标签**，因此 P0 必须写入：
  - 标题（TITLE）
  - 歌手（ARTIST）
  - 专辑（ALBUM）
- 若标签写入失败，仍保留正确文件名，并在 UI 标记“标签未写入”。

**目录约定（v0.6 起：批次下载默认按歌单建子目录）**

| 平台 | 默认建议 |
|---|---|
| Android | 用户选择的 SAF 目录下，按歌单名建子目录（`DocumentFile.createDirectory`）；旧任务仍平铺 |
| Windows | 用户选择的下载目录下，按歌单名建子目录；默认 `文档\QM-Download` |

一次“下载所选”= 一个 `DownloadBatch`，记录歌单 id / 标题 / 子目录名 / 目标根 / 音质 / 曲目数。
任务记录增加 `batchId / subDir / seq`，任务 ID 规则：`batchId != null` 时为 `batchId:songMid:quality`，
否则沿用旧规则 `songMid-quality`（避免同歌跨批次互相覆盖）。批次存储：
Desktop `data/batches.json`、Android `filesDir/download_batches.json`。

> 官方 Android 本地目录常量是 `/qqmusic/song`，但本项目**不强制**该路径；
> 用户选择的目录优先，官方识别依赖标签而非固定目录。

**规则**

- 非法字符替换：`<>:"/\|?*` 及控制字符 → `_`；
- 去掉首尾空格/点；
- 长度限制：Windows 单段 ≤ 120 字符，总路径 ≤ 240；
- 空名兜底：`未知歌手-未知歌曲`；
- 扩展名按实际档位：`.flac` / `.mp3`；
- 下载完成后校验文件大小（若服务端有 `Content-Length`）；
- 标签写入失败不阻断下载，只记录警告。

### 5.5 路径选择

**Android**

- `ACTION_OPEN_DOCUMENT_TREE` 选择目录；
- `takePersistableUriPermission` 保存授权；
- 通过 `DocumentFile` 创建文件；
- 权限失效时提示重新选择。

**Windows**

- `JFileChooser` 目录选择；
- 默认 `%USERPROFILE%\Music\QM-Download`；
- 路径写入配置；不存在时自动创建；
- 只允许绝对路径，拒绝系统目录/只读目录。

### 5.6 元数据（P0）

- **必须写入**：TITLE、ARTIST、ALBUM；
- FLAC：Vorbis Comment；
- MP3：ID3v2（TIT2/TPE1/TALB）；
- 封面嵌入为 P1（可选）；
- 依赖选择：
  - 优先使用纯 Kotlin/JVM 可用的轻量标签库；
  - 若 Android/Desktop 兼容性不足，分别实现平台写入器；
  - 标签写入失败时保留文件并提示，不删除下载。

---

## 6. UI/UX 设计

### 6.1 页面清单

| 页面 | Android | Desktop | 说明 |
|---|---|---|---|
| 登录 | 全屏 | 独立窗口/主窗口覆盖层 | 二维码 + 状态 + 刷新 |
| 首页 | 单列 | 左侧账号 + 歌单列表 | 账号头像/昵称、歌单 |
| 歌单详情 | 单列 | 右侧歌曲列表 | 封面头、全选、下载 |
| 下载队列 | 单列 | 底部/右侧面板 | 进度、失败重试、取消 |
| 设置 | 单列 | 设置窗口/页 | 路径、音质、并发、命名 |

### 6.2 响应式规则

Compose `BoxWithConstraints`：

| 宽度 | 布局 |
|---|---|
| < 600dp | 单列，底部导航/返回 |
| 600–839dp | 单列加宽，歌单卡片双列 |
| ≥ 840dp（平板/桌面） | 左侧歌单列表 + 右侧详情两栏 |
| 桌面窗口 < 900px | 折叠为单栏 + 汉堡菜单 |

### 6.3 视觉

- 主题、圆角、字体、颜色复用 Miuix；
- 歌单封面行、歌曲行参考：
  - `LibraryPlaylistRow`
  - `QqMusicPlaylistDetailScreen` 的头部/行样式
  - `ResponsiveSongPagerList`
- 图标使用 Miuix Icons；
- 登录页与 NeriPlayer 的 QQ 登录页保持一致（二维码卡片 + 状态文案 + 刷新/取消）。

### 6.4 交互

- 歌单页：单击进入；长按进入多选（可选）；
- 歌曲页：复选框 + 全选/取消全选 + “下载所选/全部”；
- 下载中：进度条、速度、剩余时间；失败可重试；
- 设置：音质（无损/320k/128k）、并发数、命名模板、保存目录。

---

## 7. 平台实现细节

### 7.1 Android

| 项 | 方案 |
|---|---|
| minSdk | 28（与 NeriPlayer 对齐） |
| 权限 | `INTERNET`、`POST_NOTIFICATIONS`（13+）、前台服务 |
| 下载服务 | `ForegroundService` + `dataSync` 类型 |
| 存储 | SAF 目录，不申请全盘存储权限 |
| 通知 | 进度通知、完成/失败通知 |
| 后台限制 | 厂商省电策略提示；下载中保持前台服务 |
| 打包 | `assembleRelease` + 独立 keystore |

### 7.2 Windows（免安装便携版）

| 项 | 方案 |
|---|---|
| UI | Compose Desktop（JVM） |
| 分发形式 | **免安装便携版**：一个文件夹，双击 `QM-Download.exe` 运行；不写注册表、不装服务 |
| 最低系统 | Windows 10 1809+（jpackage 要求） |
| 运行数据 | 放在 **exe 所在目录**：`data/config.json`、`data/session.json`、`data/logs/`、`data/cache/` |
| 下载目录 | 用户在设置中选择，可指向任意可写目录（默认 `文档\QM-Download`） |
| 路径 | `JFileChooser` 选择目录 |
| 打包 | Compose Desktop `createDistributable` 生成 app-image，压缩为 zip；MSI 仅作为可选项 |
| 图标 | `.ico` |
| 签名 | P1 考虑代码签名，减少 SmartScreen 提示 |
| 权限 | 若放在 `Program Files` 可能无写权限；文档建议放在用户目录或写权限目录 |

### 7.3 共享代码边界

| 层 | 共享 | 平台特有 |
|---|---|---|
| 网络/JSON | ✅ | — |
| 登录/二维码 | ✅ | 二维码显示方式 |
| 歌单/VKey | ✅ | — |
| 下载队列 | ✅ 状态机/任务 | 文件写入、通知/进度 UI |
| 持久化 | 接口共享 | 实现不同 |
| UI | 组件/主题尽量共享 | 页面壳/导航 |

---

## 8. 构建与打包

### 8.1 环境

- JDK 17（Compose Desktop 与 Android 均可用）；
- Android SDK（compileSdk 与 NeriPlayer 对齐）；
- Gradle Wrapper 固定版本；
- Windows 打包需 WiX（`packageMsi`）或 jpackage 自带工具。

### 8.2 命令（设计目标）

```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:assembleRelease
./gradlew :desktopApp:run
./gradlew :desktopApp:createDistributable
./gradlew :desktopApp:packageMsi   # 可选，不作为默认分发
```

> 默认产物是 `desktopApp/build/compose/binaries/main/app/QM-Download/` 便携目录，
> 压缩为 zip 发布；运行数据写入该目录下 `data/`。

### 8.3 版本与签名

- `versionName`/`versionCode` 统一在 `gradle.properties`；
- Android keystore 不进仓库；CI 用 secrets；
- Windows 可选签名证书；
- 打包产物命名：`QM-Download-<version>-android.apk`、`QM-Download-<version>-windows.exe`。

---

## 9. 测试计划

### 9.1 单元测试（core）

- 分享链接解析：合法/非法/带参数/短链；
- 文件名清洗：非法字符、空值、超长、重复；
- 音质候选链：无损→320→128 降级；
- JSON 解析：歌单/歌曲/资料；
- 错误映射：1000/2001/104003/104009；
- 会话序列化/反序列化。

### 9.2 集成测试

- 用 MockWebServer 模拟：
  - 登录二维码状态；
  - 歌单分页；
  - VKey 成功/无权限/空 purl；
  - 下载 200/206/403；
- 不把真实 Cookie 写入测试。

### 9.3 手工验收

- Android 真机：登录、歌单、全选下载、SAF 路径、断网/恢复、Cookie 过期；
- Windows：登录、歌单、路径选择、长路径、窗口缩放、EXE 启动；
- 双端下载文件可播放、文件名正确、无重复。

---

## 10. 代码复用与迁移

| NeriPlayer 源文件 | 目标 | 动作 |
|---|---|---|
| `QQMusicQrLoginClient.kt` | `core/auth` | 复制，去 Android 依赖 |
| `QQMusicSessionStore.kt` | `core/auth` + 平台实现 | 拆接口/实现 |
| `SettingsAccountProfiles.kt` 中 QQ 资料函数 | `core/profile` | 抽取为纯 OkHttp |
| `QQMusicLibraryRepository.kt` | `core/library` | 复制，去掉 Android/AppContainer |
| `QQMusicPlaybackRepository.kt` | `core/playback` | 复制，保留稳定 `guid` 修复 |
| `AudioDownloadSourceResolver.resolveQq` | `core/download` | 改为纯 URL 解析 |
| `AudioDownloadManager` | 平台下载器 | 参考，不直接复制（Android 耦合） |
| `QqMusicPlaylistDetailScreen` | UI 参考 | 复制布局/交互，不复制播放逻辑 |
| Miuix `miuix-*` | 依赖 | 直接依赖 Miuix 多平台构件 |

> 复制代码时必须保留 GPL 头与来源说明；新文件包名统一 `moe.ouom.qmdownload`。

---

## 11. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| Cookie 过期 | 登录/歌单/下载失败 | 统一 `AuthExpired`，引导重新扫码；启动时校验 |
| QQ 接口变更 | 功能失效 | 接口集中到 core；保留探针脚本；错误日志 |
| VKey `guid`/签名变化 | 下载失败 | 稳定 guid；版本升级时回归 |
| 音质权益差异 | 无损不可用 | 候选链降级；显示实际档位 |
| 加密文件（ekey） | 无法解密 | 明确提示不支持，不绕过 DRM |
| Windows 路径/权限 | 写入失败 | 路径校验、错误提示、默认目录 |
| Android SAF 权限丢失 | 下载失败 | 重新选择目录；持久化 URI 检查 |
| GPL 合规 | 法律风险 | 全项目 GPL-3.0，保留版权头 |
| 腾讯风控 | 账号风险 | 低频、串行/低并发、不批量滥用 |

---

## 12. 里程碑

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M0 | 仓库/文档/CI 骨架 | 可编译空壳 |
| M1 | core 登录 + 资料 + 歌单 | JVM 单测通过 |
| M2 | Android MVP | 登录→歌单→下载→SAF |
| M3 | Desktop MVP | 登录→歌单→下载→路径 |
| M4 | 分享链接 + 响应式 UI | 双端验收 |
| M5 | 打包/签名/回归 | APK + EXE 发布包 |

---

## 13. 验收标准

1. 扫码登录后显示头像/昵称；
2. 账号歌单列表可加载，详情可分页；
3. 单选/全选下载，进度可见，失败可重试；
4. 下载目录可选择并持久化；
5. 分享链接可解析并进入歌单；
6. Cookie 过期时提示重新登录；
7. 宽/窄窗口布局正常；
8. Windows EXE 与 Android APK 均可安装运行；
9. 文件可播放、命名正确、无重复；
10. 文档/许可/构建说明完整。

---

## 14. 待确认项（设计阶段）

| 编号 | 问题 | 建议默认 |
|---|---|---|
| D3 | Windows 最低版本 | Windows 10 1809+ |
| D4 | Android minSdk | 28 |
| D5 | 默认音质 | 无损优先，自动降级 |
| D6 | 文件命名 | `歌手-歌曲名.ext`（对齐 QQ 音乐 PC 命名选项） |
| D6a | 元数据 | P0 写入 TITLE/ARTIST/ALBUM；封面 P1 |
| D7 | 是否嵌入封面 | P1 再做 |
| D8 | 是否断点续传 | P1 支持 Range |
| D11 | 是否保留“下载全部歌单” | 建议 P1 |
| D12 | 是否需要系统托盘/最小化 | P1 |

---

## 15. 下一步

1. 你确认本设计（尤其第 14 节默认值）；
2. 我按 M0→M1 开始搭建仓库与 core 模块；
3. 每完成一个里程碑，更新 `DESIGN.md`/`REPORT.md` 与验收记录。
