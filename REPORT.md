# QM-Download 项目评估报告

> 文档版本：v0.1（2026-11-15）  
> 作者：接手前的现状梳理  
> 状态：**仅评估，未开始编码**；等待方案确认后再进入设计/实现阶段。  
> 相关项目：[NeriPlayer](../NeriPlayer/README.qqmusic.md)（本地 Fork，GPL-3.0）

---

## 0. 文档目的与阅读方式

本文档面向后续接手 QM-Download 的开发者，目标是：

1. 盘点 NeriPlayer 中**已经实现、可以借用**的 QQ 音乐能力；
2. 明确当前存在的**问题、限制与未实现项**；
3. 对 Windows EXE + Android APK 双端方案做**可行性评估**；
4. 给出推荐架构、分阶段计划与需要产品确认的决策点。

> 阅读约定：
> - “已有”= NeriPlayer 中已存在且可运行/已验证的代码；
> - “需抽取”= 逻辑存在，但和 Android/App 容器耦合，需要重构；
> - “缺失”= 当前没有实现，需要新写；
> - 所有路径相对本仓库的 `QM-Download/` 目录。

---

## 1. 项目目标（QM-Download）

| 编号 | 需求 | 优先级 |
|---|---|---|
| R1 | 扫码登录 QQ 音乐，读取账号头像/昵称 | P0 |
| R2 | 查看账号下的歌单列表 | P0 |
| R3 | 查看歌单详情，单选/全选歌曲并下载 | P0 |
| R4 | 下载文件保存路径可选 | P0 |
| R5 | 粘贴 QQ 音乐歌单分享链接，解析并下载 | P1 |
| R6 | Windows EXE | P0 |
| R7 | Android APK | P0 |
| R8 | 宽屏/窄屏自适应，风格接近 NeriPlayer | P1 |
| R9 | 只做下载，不做在线播放/评论/歌单编辑 | 范围约束 |

**非目标（本期不做）**：

- 在线播放器、歌词、评论、歌单编辑、WNS 登录；
- 网易云/B站/YouTube 等其他音源；
- 绕过会员/付费/DRM；只下载账号本来有权访问的内容。

---

## 2. NeriPlayer 现有能力盘点

### 2.1 QQ 音乐登录与账号

| 能力 | 现有实现 | 状态 | 说明 |
|---|---|---|---|
| 扫码登录 | `QQMusicQrLoginClient` 四步：`ptqrshow` → `ptqrlogin` → `check_sig` → `QQConnectLogin.LoginServer/QQLogin` | ✅ 可用 | 最终拿到 `uin`、`qm_keyst/music_key` |
| 登录态持久化 | `QQMusicSessionStore`（SharedPreferences） | ✅ 可用 | 只存 Cookie 串；Android 专用 |
| 账号资料（昵称/头像） | `SettingsAccountProfiles.kt` 的 `loadQqMusicAccountProfile()` | ⚠️ 可用但位置不对 | 接口 `fcg_get_profile_homepage.fcg`，解析 `creator.nick` / `headpic`；当前写在 app 模块，依赖 `AppContainer` |
| 登录态失效识别 | 各接口自行判断 `code` | ⚠️ 不统一 | Cookie 过期会返回 `1000/2001`，需要统一检测并提示重新登录 |

关键文件：

- [`QQMusicQrLoginClient.kt`](../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/auth/QQMusicQrLoginClient.kt)
- [`QQMusicSessionStore.kt`](../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/auth/QQMusicSessionStore.kt)
- [`SettingsAccountProfiles.kt`](../NeriPlayer/app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/auth/SettingsAccountProfiles.kt)

### 2.2 歌单读取

| 能力 | 接口 | 状态 | 文件 |
|---|---|---|---|
| 账号歌单列表 | `music.musicasset.PlaylistBaseRead / GetPlaylistByUin` | ✅ 可用 | `QQMusicLibraryRepository.userPlaylists()` |
| 歌单详情/分页 | `music.srfDissInfo.DissInfo / CgiGetDiss` | ✅ 可用 | `playlistTracksPage()` |
| 公开歌单/分享歌单 | 同上（`CgiGetDiss`） | ⚠️ 原理可行，**未做链接解析** | 需要新增 URL → 歌单 ID 解析 |
| 歌曲详情 | `music.pf_song_detail_svr / get_song_detail_yqq` | ✅ 可用 | 取 `songId`、`media_mid`、`songType` |
| 搜索结果 | `music.search.SearchCgiService` | ✅ 可用 | 本项目不要求搜索，可不用 |

关键文件：

- [`QQMusicLibraryRepository.kt`](../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/library/QQMusicLibraryRepository.kt)
- [`QqPlaylistDetailViewModel.kt`](../NeriPlayer/app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/playlist/QqPlaylistDetailViewModel.kt)

### 2.3 下载与播放地址

| 能力 | 实现 | 状态 | 说明 |
|---|---|---|---|
| VKey 解析 | `QQMusicPlaybackRepository.resolve()` | ✅ 可用 | 无损 → 320k → 128k 候选链 |
| 音质选择 | `QQMusicQualityStore` / 下载音质设置 | ✅ 可用 | Android 设置项，需桌面端重建 |
| 下载队列/文件写入 | `AudioDownloadManager` / `AudioDownloadSourceResolver.resolveQq()` | ⚠️ 可用但 Android 耦合 | 依赖 Room、通知、Android 存储/SAF |
| 文件命名/标签 | 下载管理器现有命名规则 | ⚠️ 未统一 | 需要为 QM-Download 定义 `歌手-歌曲名.ext`（对齐 QQ 音乐命名选项）并写入 TITLE/ARTIST/ALBUM 标签 |

关键文件：

- [`QQMusicPlaybackRepository.kt`](../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/playback/QQMusicPlaybackRepository.kt)
- [`AudioDownloadSourceResolver.kt`](../NeriPlayer/modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/player/download/source/AudioDownloadSourceResolver.kt)
- `modules/download/runtime/.../AudioDownloadManager.kt`

### 2.4 UI 可复用资产

| 资产 | 位置 | 复用建议 |
|---|---|---|
| Miuix 设计体系 | `NeriPlayer/np-submodule/miuix` | ✅ 组件库支持 Android + Desktop（`jvm("desktop")` 已配置） |
| 登录页 | `QqMusicQrLoginActivity` / Compose 登录页 | 需重做 Android 页面；桌面另做窗口 |
| 歌单详情布局 | `QqMusicPlaylistDetailScreen` | 可参考顶栏、封面头、列表行、多选交互 |
| 通用歌单行/歌曲行 | `LibraryPlaylistRow`、`ResponsiveSongPagerList` | 抽取成共享 Compose 组件 |
| 设置页风格 | `Settings*` 页面 | 参考“音质/路径/账号”分组样式 |
| 自适应布局 | `CreatorDetailAdaptiveLayout` 等 | 宽屏分栏、窄屏单列的参考 |

> Miuix 的 Desktop target 存在于 `np-submodule/miuix/*/build.gradle.kts`，说明 UI 组件库本身可跨平台；但 NeriPlayer 的 **app 模块是 Android-only**，不能直接整体编译到 Windows。

### 2.5 当前不具备的能力

| 缺失项 | 影响 | 备注 |
|---|---|---|
| Windows 客户端 | 无法直接复用 Android UI | 需要 Compose Desktop 或另一套 UI |
| 分享链接解析 | R5 无法实现 | 需新增 `y.qq.com/n/ryqq_v2/playlist/{id}` 解析 |
| 共享核心模块 | 登录/歌单/下载逻辑散落在 platform、download、app | 需要抽成 KMP `core` |
| 桌面路径选择 | 无 | Windows 用 `JFileChooser`/AWT；Android 用 SAF |
| 统一错误模型 | 各仓库抛 `IOException`/字符串 | 需要 `AuthExpired`、`NoPermission`、`NoResource` 等类型 |
| 下载进度/断点续传统一接口 | Android 下载器与 UI 绑定 | 需要抽象 `DownloadTask` |

---

## 3. 当前存在的问题

### 3.1 登录态与接口稳定性

1. **Cookie 过期**：会同时导致搜索 `2001`、播放/写歌单 `1000`。  
   解决方向：启动时/失败时做一次轻量校验，统一提示“重新扫码登录”。
2. **QQ 接口会变**：`guid` 随机导致 VKey `104009/invalidq`；已改为稳定 `guid`（优先 `wid`，否则 `uin`）。  
   桌面端也必须使用稳定 `guid`，不能每次随机。
3. **音质/版权差异**：同一首歌不同账号/曲目能拿到的档位不同；必须保留候选链与“实际档位”提示。
4. **Web 会话限制**：歌单写、评论写依赖 App `authst`/WNS；本项目只读 + 下载，不需要写权限，风险较低。

### 3.2 代码结构问题

1. 账号资料加载写在 `app` 模块，无法被 Windows 复用；
2. `QQMusicLibraryRepository` 依赖 OkHttp 与 Android 无直接耦合，但仍需抽公共模块；
3. 下载器与 Android 的 Room、通知、SAF、前台服务耦合，桌面端无法直接复用；
4. `QQMusicSessionStore` 使用 Android `SharedPreferences`，桌面需要 `expect/actual`；
5. UI 页面大量使用 Android 专属 API（`LocalContext`、`Activity`、`Toast`、`DownloadManager`）。

### 3.3 产品/合规问题

1. 本项目是 NeriPlayer 的衍生（GPL-3.0），**QM-Download 必须继续 GPL-3.0 开源**；
2. QQ 音乐接口非官方公开 API，可能随时变更；只应下载账号有权访问的内容；
3. 不得绕过会员/付费/DRM，不得再分发下载文件；
4. Windows EXE 需要签名/杀软误报处理（后续单独讨论）。

---

## 4. 双端技术可行性

### 4.1 方案对比

| 方案 | Android | Windows | 代码复用 | 评价 |
|---|---|---|---|---|
| **Kotlin Multiplatform + Compose Multiplatform**（推荐） | 原生 Compose | Compose Desktop EXE | 高（core 80%+，UI 50%+） | 与现有 Kotlin 代码最匹配；Miuix 有 Desktop target |
| Android 原生 + Windows 另写（.NET/Electron） | 高 | 中 | 低（两套） | Windows 开发量大，风格难统一 |
| Flutter 重写 | 中 | 中 | 低（Dart 重写） | 放弃现有 Kotlin 资产，不推荐 |
| Web + Electron/Tauri | 低（Android 另做） | 高 | 低 | 体积大、下载能力弱，不推荐 |

### 4.2 推荐：KMP + Compose Multiplatform

```
QM-Download/
├── core/                 # KMP 共享：认证、QQ API、歌单、VKey、下载任务模型
├── androidApp/           # Android APK：Compose + SAF + 前台下载服务
├── desktopApp/           # Windows EXE：Compose Desktop + JFileChooser
└── ui/                   # 可选：共享 Compose UI（宽/窄屏）
```

**可行性判断：**

| 目标 | 可行性 | 理由 |
|---|---|---|
| Android APK | **高** | 现有 platform 模块与 Compose UI 可大量复用 |
| Windows EXE | **中高** | 核心 Kotlin 可复用；UI 需 Compose Desktop 重做/移植；Miuix 有 Desktop target |
| 宽窄屏自适应 | **高** | Compose `BoxWithConstraints` + 现有自适应布局思路 |
| 分享链接解析 | **高** | URL 规则明确，`CgiGetDiss` 已可用 |

### 4.3 关键技术选型建议

| 领域 | 建议 |
|---|---|
| 语言/UI | Kotlin + Compose Multiplatform |
| 网络 | OkHttp（JVM/Android 通用） |
| JSON | `org.json` 或 kotlinx.serialization（建议统一 kotlinx.serialization） |
| 协程 | kotlinx.coroutines |
| 桌面打包 | Compose Desktop `packageDistributionForCurrentOS` / `packageMsi` |
| Android 下载 | WorkManager/Foreground Service + SAF |
| 桌面下载 | OkHttp 流式写文件 + 协程并发队列 |
| 配置存储 | Android DataStore/SharedPreferences；Desktop 免安装版，配置/会话/日志放在 exe 同目录 `data/` |
| 二维码 | Android：Compose 图片；Desktop：Compose + ZXing 生成/显示（登录二维码是服务端图片，直接显示即可） |

---

## 5. 建议的模块拆分（供后续设计确认）

### 5.1 `core`（共享）

- `auth`
  - `QQMusicQrLoginClient`（纯 Kotlin/OkHttp）
  - `QQMusicSession`、`SessionStore`（expect/actual 持久化）
  - `QQMusicProfileRepository`（头像/昵称）
- `library`
  - `QQMusicPlaylistRepository`（账号歌单、歌单详情、分享链接解析）
- `download`
  - `QQMusicUrlResolver`（VKey + 音质链）
  - `DownloadTask`、`DownloadQueue`、`DownloadState`
  - 文件名/目录策略
- `model`
  - `QQMusicPlaylist`、`QQMusicTrack`、`QQMusicAccountProfile`

### 5.2 `androidApp`

- 登录页、账号页、歌单页、下载页；
- SAF 目录选择、通知、前台服务、断点/失败重试；
- 复用 Miuix 主题与 NeriPlayer 视觉。

### 5.3 `desktopApp`

- 主窗口：左侧歌单列表 / 右侧歌曲列表（宽屏）；
- 窄窗口：单列导航；
- 登录窗口/二维码弹窗；
- 设置页：下载目录、音质、并发数；
- 系统托盘/最小化（可选）。

---

## 6. 实施阶段（建议）

| 阶段 | 内容 | 交付物 | 预计 |
|---|---|---|---|
| P0 | 建仓、GPL 声明、CI、文档 | `QM-Download` 骨架 | 0.5 天 |
| P1 | 抽取 core：登录/资料/歌单/VKey | 可运行的 JVM 单元测试 | 3–5 天 |
| P2 | Android APK：登录、歌单、下载、SAF | 可安装 APK | 5–7 天 |
| P3 | Desktop EXE：登录、歌单、下载、路径选择 | 可运行 EXE | 5–7 天 |
| P4 | 宽窄屏自适应、UI 统一、分享链接 | 双端 UI 验收 | 3–5 天 |
| P5 | 打包、签名、异常/断网/过期测试 | 发布包 + 测试报告 | 2–3 天 |

> 时间仅为粗估，取决于是否复用 NeriPlayer 现有下载器以及 UI 移植深度。

---

## 7. 验收标准（建议）

1. 扫码登录后能显示头像、昵称、账号标识；
2. 能列出账号歌单；点击进入详情；单选/全选后加入下载队列；
3. 下载路径可选择；下载完成文件可播放；文件名/目录符合约定；
4. 粘贴示例链接 `https://y.qq.com/n/ryqq_v2/playlist/9735321048?...` 能打开歌单并下载；
5. Cookie 过期时提示重新登录，不崩溃；
6. Windows 窗口拉宽/缩窄时布局合理；
7. 双端均能构建出安装包/EXE；
8. GPL-3.0 许可与来源声明完整。

---

## 8. 待确认的决策点（需要你拍板）

| 编号 | 问题 | 建议 |
|---|---|---|
| D1 | 技术栈是否采用 **KMP + Compose Multiplatform**？ | ✅ 推荐 |
| D2 | 是否直接复制 NeriPlayer 代码到 `core`（而非依赖整个 Android app）？ | ✅ 推荐复制/重构，降低耦合 |
| D3 | Windows 最低版本？ | Windows 10 1809+（Compose Desktop 要求） |
| D4 | Android 最低版本？ | Android 9（API 28），与 NeriPlayer 对齐 |
| D5 | 默认下载音质？ | 无损优先，失败降级 320k/128k |
| D6 | 文件命名规则？ | `歌手-歌曲名.ext`（对齐 QQ 音乐 PC 命名选项），并写入 TITLE/ARTIST/ALBUM 标签 |
| D7 | 是否需要下载 FLAC 元数据/封面？ | 建议 P1，可后置 |
| D8 | 是否支持断点续传？ | 建议 Android/桌面都支持简单 Range 续传 |
| D9 | 是否保留“仅下载、不播放”的边界？ | ✅ 建议保持 |
| D10 | 是否接受 GPL-3.0 开源？ | 必须接受（衍生作品） |

---

## 9. 结论与建议

### 9.1 评价

- **Android APK：高可行性**。NeriPlayer 已具备登录、歌单、VKey、下载、Miuix UI 的完整经验，主要工作是抽取和解耦。
- **Windows EXE：中高可行性**。核心 Kotlin 可复用；UI 需要 Compose Desktop 重做，但 Miuix 本身有 Desktop target，风格可以接近。
- **最大风险不是技术，而是 QQ 接口稳定性与登录态过期**；需要把“重新登录”做成常规流程，而不是异常。
- **分享链接解析**是低成本高价值功能，建议 P1 必做。

### 9.2 推荐结论

> 采用 **Kotlin Multiplatform + Compose Multiplatform**，  
> 先抽取 `core`，再分别做 Android 与 Windows 壳；  
> UI 复用 Miuix 与 NeriPlayer 的歌单/列表设计；  
> 本期只做“登录 + 歌单 + 下载 + 路径选择 + 分享链接”，不做播放/评论/写歌单。

---

## 10. 附录

### 10.1 关键接口清单

| 用途 | module / method / URL |
|---|---|
| 扫码登录 | `QQMusicQrLoginClient` 四步 |
| 账号资料 | `c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg` |
| 我的歌单 | `music.musicasset.PlaylistBaseRead / GetPlaylistByUin` |
| 歌单详情 | `music.srfDissInfo.DissInfo / CgiGetDiss` |
| 歌曲详情 | `music.pf_song_detail_svr / get_song_detail_yqq` |
| 播放地址 | `music.vkey.GetVkey / UrlGetVkey` |
| 分享链接 | `https://y.qq.com/n/ryqq_v2/playlist/{id}`（待实现解析） |

### 10.2 参考文件索引

- NeriPlayer QQ 功能说明：[`README.qqmusic.md`](../NeriPlayer/README.qqmusic.md)
- 探针与能力复盘：[`VERIFIED_CAPABILITIES.md`](../NeriPlayer/tools/qq-probe/VERIFIED_CAPABILITIES.md)
- 登录：[`QQMusicQrLoginClient.kt`](../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/auth/QQMusicQrLoginClient.kt)
- 会话：[`QQMusicSessionStore.kt`](../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/auth/QQMusicSessionStore.kt)
- 歌单：[`QQMusicLibraryRepository.kt`](../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/library/QQMusicLibraryRepository.kt)
- 播放/VKey：[`QQMusicPlaybackRepository.kt`](../NeriPlayer/modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/playback/QQMusicPlaybackRepository.kt)
- 下载集成：[`AudioDownloadSourceResolver.kt`](../NeriPlayer/modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/player/download/source/AudioDownloadSourceResolver.kt)
- 账号资料：[`SettingsAccountProfiles.kt`](../NeriPlayer/app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/auth/SettingsAccountProfiles.kt)

---

## 11. 下一步

1. 你确认本报告与推荐方向（尤其 D1/D2/D9/D10）；
2. 我据此编写《QM-Download 技术方案与详细设计》，包含：
   - 模块/包结构；
   - `core` 接口定义；
   - 双端 UI 线框与响应式规则；
   - 下载任务状态机与文件命名；
   - 分享链接解析；
   - 构建/打包/签名流程；
   - 测试与验收清单；
3. 方案再次确认后，才开始写代码。
