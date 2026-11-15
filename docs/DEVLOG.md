# QM-Download 开发日志

> 记录每个阶段的决策、变更与验证结果，便于接手者追溯。  
> 规则：只追加，不删除历史；每次提交/阶段结束补一条。

---

## 2026-11-15 · 立项与设计确认

### 决策

- 技术栈：**Kotlin Multiplatform + Compose Multiplatform**（core 共享，Android/Desktop 双壳）。
- 复用方式：从 NeriPlayer 复制/重构，不直接依赖整个 Android app。
- 范围：登录 + 账号资料 + 歌单 + 选集下载 + 路径选择 + 分享链接；不做播放/评论/歌单编辑。
- 许可：GPL-3.0，复制代码保留原版权头。

### 用户新增约束（本日确认）

1. **文件命名对齐 QQ 音乐官方**
   - PC 官方设置项提供“歌手-歌曲名”；
   - 默认采用 `{artist}-{title}.{ext}`（连字符两侧不加空格）；
   - P0 必须写入 TITLE/ARTIST/ALBUM 标签，保证官方客户端识别；
   - 官方 Android 目录常量 `/qqmusic/song` 仅作参考，不强制；用户选择的目录优先。
2. **Windows 免安装便携版**
   - 发布为文件夹 + `QM-Download.exe`，不写注册表、不装服务；
   - 配置/会话/日志/缓存放在 exe 同目录 `data/`；
   - 音乐下载路径由用户选择；
   - 默认打包 `createDistributable` 并压缩 zip；MSI 仅可选。

### 文档

- `REPORT.md`：现状、问题、可行性、推荐方案。
- `DESIGN.md`：架构、模块、接口、流程、UI、构建、测试、风险。
- `README.md`：项目入口。

### 待办（M0）

- [x] 建立 Gradle 工程（core / desktopApp / androidApp）
- [x] 验证 JDK 17 + Kotlin 2.4.21 + Compose 1.12.1 可编译
- [x] 验证 Compose Desktop `createDistributable` 产物
- [x] 加入 `androidApp` 并验证 `assembleDebug`

---

## 2026-11-15 · M0 骨架（core + desktop）

### 完成

- 建立 `core`（纯 Kotlin/JVM，OkHttp + Coroutines + Serialization）：
  - `moe.ouom.qmdownload.core.model.QQMusicSession`
- 建立 `desktopApp`（Compose Desktop 1.12.1 + Material3）：
  - 最小窗口 `QM-Download`，占位文案。
- 复制 Gradle Wrapper（9.6.1）。
- `local.properties` 指向 `C:\Dev\Android\Sdk`。

### 决策/变更

- 为降低 M0 风险，`core` 先做 **Kotlin/JVM 共享模块**；
  Android/Desktop 都依赖 JVM 产物。后续如需 iOS 再迁 KMP target。
- 仓库顺序改为 `mavenCentral()` 优先，`google()` 其后；Compose Desktop 依赖仍可能从 Google Maven 拉取。
- Gradle 增加 HTTP 超时：
  - `systemProp.org.gradle.internal.http.connectionTimeout=120000`
  - `systemProp.org.gradle.internal.http.socketTimeout=120000`

### 验证

**core + desktop 编译**

```powershell
$env:JAVA_HOME='C:\Dev\jdk17'
$env:GRADLE_USER_HOME='C:\Dev\gradle-home'
.\gradlew.bat :desktopApp:compileKotlin --no-daemon --console=plain
```

结果：

```
BUILD SUCCESSFUL
```

首次失败原因：`dl.google.com` 下载 `runtime-desktop-1.12.1.jar` 超时；
调整仓库顺序并增加超时后通过。

**桌面免安装产物**

```powershell
.\gradlew.bat :desktopApp:createDistributable --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，产物目录：

```
desktopApp/build/compose/binaries/main/app/QM-Download/
  ├─ QM-Download.exe
  ├─ app/
  └─ runtime/
```

**Android APK**

```powershell
.\gradlew.bat :androidApp:assembleDebug --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`。  
注意：AGP 9.4.1 **内置 Kotlin 支持**，不能再应用 `org.jetbrains.kotlin.android` 插件，
否则会报 “plugin is no longer required since AGP 9.0”。

### 已知问题

- Android 端目前是最小 Activity 占位页，尚未接 Compose；
- 桌面端是 Compose 占位页；
- 下载/登录/歌单均未实现（M1 开始）；
- 便携包尚未压缩为 zip，也未做启动脚本/图标；
- 桌面仅配置了 MSI target，便携版依赖 `createDistributable`，后续可移除 MSI 或保留可选。

### 下一步

1. 把 `QQMusicSession`、g_tk、分享链接解析、歌单模型迁入 `core`；
2. 实现登录模块（QR 四步 + 会话持久化）；
3. 实现账号资料与歌单读取；
4. Android/Desktop 接入登录页与歌单页；
5. 实现 VKey 解析与下载队列。

---

## 2026-11-15 · M1 登录 + 账号资料 + 歌单（core + Desktop）

### 完成

**core**

- 从 NeriPlayer 复制并重打包 `QQMusicQrLoginClient`：
  - 保留 GPL 版权头；
  - 包名改为 `moe.ouom.qmdownload.core.auth`；
  - 仍是纯 OkHttp/JVM，无 Android 依赖。
- 新增 `QQMusicSessionSupport.kt`：
  - `qqMusicHash33`（g_tk）
  - `parseQQMusicCookie` / `cookieValues`
  - `QQMusicSessionStore` 接口（Android/Desktop 各自实现）
- 新增歌单模型：`QQMusicPlaylist`、`QQMusicTrack`、`QQMusicPlaylistTracks`。
- 新增 `QQMusicLibraryRepository`：
  - `myPlaylists()`：`GetPlaylistByUin`
  - `playlistTracksPage()`：`CgiGetDiss`
  - 统一错误：1000/2001 → `QmError.AuthExpired`
- 新增 `QQMusicProfileRepository`：
  - `fcg_get_profile_homepage.fcg`
  - 昵称/头像解析。
- 新增 `QQMusicShareLinkParser`：
  - 支持 `ryqq_v2/playlist/{id}`、`ryqq/playlist/{id}`、`/playlist/{id}`、纯数字 ID；
  - 校验 `*.qq.com`。
- 单元测试：
  - 分享链接解析 5 个用例；
  - Cookie/g_tk 解析 3 个用例。

**desktopApp**

- 新增便携会话存储：exe 同目录 `data/session.properties`。
- 新增登录页：
  - 获取二维码；
  - 每 2 秒轮询；
  - 扫码/已扫码/过期/拒绝提示；
  - 登录成功保存会话。
- 新增主界面：
  - 左侧账号信息 + 歌单列表；
  - 右侧歌单歌曲列表；
  - 点击歌单懒加载歌曲。

### 验证

```powershell
.\gradlew.bat :core:test :desktopApp:compileKotlin :androidApp:assembleDebug --no-daemon
```

结果：

```
BUILD SUCCESSFUL
8 tests completed
```

### 已知问题

- Desktop 登录页尚未做真实扫码人工验收（需要启动窗口）；
- Android 仍是占位 Activity，未接 Compose；
- 会话存储是明文 `Properties`，后续需要加密/权限收紧；
- `myPlaylists` 字段名（`tid/dirId/dissid`）需要真实账号回归；
- 分享歌单只解析了 ID，尚未做“粘贴 → 打开”的 UI；
- 下载（VKey/文件写入）尚未实现。

### 下一步

1. 启动 Desktop 做真实扫码 + 歌单验收；
2. Android 接入 Compose 登录/歌单页；
3. 抽取 VKey 解析与下载队列；
4. 分享链接输入框接入 Desktop/Android。

---

## 2026-11-15 · M1 Android UI

### 完成

- Android 接入 Compose（AGP 9 内置 Kotlin + Compose Compiler 插件）。
- `QmApplication`：
  - 全局 OkHttp；
  - `AndroidSessionStore`（SharedPreferences 保存 Cookie）。
- `MainActivity`：
  - 扫码登录页：二维码、轮询、过期/拒绝提示；
  - 登录后账号资料 + 歌单列表；
  - 歌单详情歌曲列表；
  - `BoxWithConstraints` 响应式：>700dp 左右分栏，窄屏单列；
  - 窄屏歌曲页支持“返回歌单”。

### 验证

```powershell
.\gradlew.bat :androidApp:assembleDebug --no-daemon
```

结果：

```
BUILD SUCCESSFUL
```

### 已知问题

- 尚未在真机完成扫码/歌单人工验收；
- 窄屏下歌单列表与歌曲页切换是简单状态，不是 Navigation；
- Android 会话仍是明文 SharedPreferences；
- 下载功能未接；
- Desktop 与 Android 的 UI 代码重复，后续抽 `ui` 共享组件。

### 下一步（M2）

1. core 增加 VKey 解析（稳定 guid + 音质降级）；
2. 下载任务模型/队列/文件写入；
3. Android SAF 目录选择；
4. Desktop JFileChooser 目录选择；
5. 双端歌单页加“全选/下载”。

---

## 2026-11-15 · M2 VKey + 下载 + 路径选择

### 完成

**core**

- `QQMusicUrlResolver`：
  - 稳定 `guid`（优先使用 uin）；
  - F000 → M800 → M500 降级；
  - `vecPrefix/downloadfrom/source` 参数；
  - 1000/2001 → `AuthExpired`，104003 → `NoPermission`。
- `QQMusicDownloader`：OkHttp 流式写 OutputStream，进度回调。
- `DownloadFileNames`：
  - `歌手-歌曲名.ext`；
  - 非法字符清洗、120 字符上限。
- `AudioTagWriter`：
  - MP3：ID3v2.3 TIT2/TPE1/TALB；
  - FLAC：Vorbis Comment 写入/替换；
  - 失败不阻断文件。

**Desktop**

- 歌单歌曲多选/全选；
- `JFileChooser` 选择下载目录，配置保存到 `data/config.properties`；
- 下载所选：逐个解析 → 下载 → 写标签；
- 目标文件重名自动加 `(1)`；
- 进度/完成/失败状态显示。

**Android**

- `AndroidDownloadManager`：
  - SAF 目录选择并持久化授权；
  - 先下载到缓存文件并写标签，再复制到 SAF；
  - 重名自动加 `(1)`。
- 歌单歌曲多选/全选、目录选择、下载按钮、进度显示。

### 验证

```powershell
.\gradlew.bat :core:compileKotlin :desktopApp:compileKotlin --no-daemon
.\gradlew.bat :androidApp:assembleDebug --no-daemon
```

结果：`BUILD SUCCESSFUL`

运行/安装：

- PC 端已启动（窗口标题 `QM-Download`，等待扫码）；
- Android APK 已安装并启动（平板 `HA27VL78`）。

### 已知问题

- 尚未实际扫码/下载验收；需要用户操作；
- Android 下载在 Activity 协程中执行，退到后台可能被系统暂停，尚未接前台服务/通知；
- 下载是串行队列，没有暂停/取消/断点续传；
- FLAC 标签写入会把整文件读入内存，超大文件需改为流式；
- 音质固定“无损优先自动降级”，尚无设置项；
- 分享链接尚未接 UI。

### 下一步（M3）

1. 用户验收登录/歌单/下载；
2. 分享链接输入框 + 打开歌单；
3. 下载设置（音质、并发、命名）；
4. Android 前台服务/通知；
5. UI 向 NeriPlayer/Miuix 风格靠拢；
6. 便携包 zip + 文档。

---

## 2026-11-15 · 反馈修复（歌单名/音质/头像/去重）

### 根因

- **歌单名不显示**：真实响应字段是 `dirName`，代码读的是 `dissname/title/name`；
  数量字段同样是 `songNum` 而不是 `songnum`；封面是 `picUrl/bigpicUrl`。
- **头像不显示**：资料接口已返回 `creator.headpic`，但 Desktop/Android 都没有渲染头像。
- **无法选音质**：解析器支持 `qualityKey`，UI 没有入口。
- **重复下载**：Desktop 一律生成 `(1)` 新文件；Android 也是直接新建。

### 修复

- core `QQMusicLibraryRepository`：
  - 标题：`dirName → dissname → title → name`；
  - 数量：`songNum → songnum → song_num`；
  - 封面：`picUrl → bigpicUrl → imgurl/logo/cover`；
  - 播放量：`play_cnt → listennum`。
- Desktop/Android：
  - 账号区显示圆形头像（OkHttp 拉取 + 平台解码）；
  - 新增音质选择：无损 / 320k / 128k，并持久化；
  - 下载前检查同名文件：存在则跳过并计入“跳过”；
  - 保留重名 `(1)` 兜底（不同音质/扩展名时）。
- Android `AndroidDownloadManager.download(..., qualityKey)`：
  - 解析前先检查 SAF 中是否已有同名文件；
  - 返回 null 表示跳过。

### 验证

```text
:desktopApp:compileKotlin  BUILD SUCCESSFUL
:androidApp:assembleDebug  BUILD SUCCESSFUL
```

PC 端已重启，Android 已重新安装并启动。

### 仍未完成

- Android 前台服务/通知（退后台可能暂停下载）；
- 暂停/取消/断点续传；
- 分享链接输入框；
- FLAC 标签写入的内存占用；
- UI 仍为 Material3，尚未完全对齐 Miuix/NeriPlayer。

---

## 2026-11-15 · 8KB 占位文件排查

### 现象

部分歌曲下载后得到约 8.4KB 的 `.flac`，无法播放。

### 排查

- 文件头是合法 `fLaC`，但 FLAC 元数据块结束后 **音频帧数为 0**；
- 实际文件只有 STREAMINFO/SEEKTABLE/VORBIS_COMMENT/PADDING，没有音频；
- 以《劇場版・戦車道行進曲！パンツァーフォー！》为例：
  - `pay.pay_play=1`、`pay.pay_down=1`、`pay_month=1`；
  - VKey 对 F000/M800/M500 全部返回 `result=104003`（无下载权限）；
  - CDN 仍返回 HTTP 200 + 8KB 元数据占位文件。

### 结论

不是下载代码写错，而是 **账号无该曲目的下载权限**；QQ CDN 用占位 FLAC
响应，导致旧逻辑把占位文件当成成功。

### 修复

- 新增 `AudioFileValidator`：
  - FLAC 必须存在音频帧；
  - MP3 必须存在有效帧同步；
  - 小于 64KB 直接判定无效。
- 下载完成后校验；无效则删除并报错：
  `该歌曲可能无版权或当前账号无下载权限`。
- 重复下载判断改为：
  - Desktop：同名文件有效才跳过；旧的 8KB 占位文件删除后重下；
  - Android：SAF 同名文件 ≥64KB 才跳过，否则删除重下。

### 验证

```text
:desktopApp:compileKotlin  BUILD SUCCESSFUL
:androidApp:assembleDebug  BUILD SUCCESSFUL
```

PC 端已重启、Android 已重新安装。

### 注意

- 付费/VIP 曲目仍无法通过本项目下载；这是账号权限，不是代码可绕过的问题。
- 建议在歌单中过滤 `pay_play=1/pay_down=1` 的曲目，或下载前明确提示。

---

## 2026-11-15 · NeriPlayer 下载实现调研

### 完成

- 阅读 NeriPlayer QQ 音乐播放/下载实现、音质设置、文件命名、完整性校验；
- 阅读 `tools/qq-probe` 的 README/FINDINGS/download_test；
- 对照 QM-Download 当前 VKey、下载、校验、命名、UI 实现；
- 新增技术文档：
  [`NERIPLAYER_QQ_DOWNLOAD_TECH.md`](NERIPLAYER_QQ_DOWNLOAD_TECH.md)。

### 主要发现

1. NeriPlayer VKey 使用 `ct=11 + authst=musicKey`，QM 当前用 `ct=24 + g_tk`；
2. NeriPlayer 在 `result != 0` 时继续降级，QM 当前直接抛错，可能中断降级；
3. NeriPlayer 下载后有长度/MD5/时长/音轨校验，QM 当前只做文件头/最小大小；
4. NeriPlayer 有 staging/Range/重试/队列/前台服务，QM 当前是串行 UI 协程；
5. NeriPlayer 默认命名是 `%title% - %artist% - %album% - %source%`，
   QM 为官方风格 `%artist%-%title%`，这是有意的差异；
6. NeriPlayer 未发现写入 ID3/Vorbis 标签，QM 手写标签是额外增强。

### 未做

- 未修改任何代码；等待确认 P0/P1/P2 范围。

---

## 2026-11-15 · 方案 B：下载引擎

### 完成

**core**

- `QQMusicUrlResolver` 重写：
  - 身份 A：`ct=11 + cv=14090008 + authst=musicKey`；
  - 身份 B 回退：`ct=24 + g_tk`；
  - `result != 0` / `purl` 空 → 继续下一档，不再整体失败；
  - 缺少 `media_mid` 时调用 `get_song_detail_yqq` 补查；
  - `downloadfrom=0`，与 NeriPlayer 对齐。
- `QQMusicDownloader.downloadToFile()`：
  - 写入 `<目标>.part`；
  - 已有 part 时 Range 续传；
  - 服务端返回 200 时从头覆盖；
  - 最多 3 次重试；
  - 协程取消；
  - 完成后原子 rename。
- `AudioFileValidator`：
  - 保留占位文件检测；
  - FLAC 增加 STREAMINFO 时长解析；
  - 与歌曲预期时长比较（0.5x–2x）；
  - 仍拒绝 <64KB 文件。

**Desktop**

- 下载改为 `downloadToFile`，支持 `.part` 续传；
- “取消”按钮：取消当前下载 Job；
- 重复文件用增强校验判断，坏文件删除重下。

**Android**

- 新增 `DownloadService` 前台服务：
  - 通知渠道 + 进度通知；
  - 通知栏“取消”；
  - 退后台继续下载；
  - 服务内串行处理选中的歌曲。
- Activity 改为提交服务，不再在界面协程里下载；
- 增加 `POST_NOTIFICATIONS` 权限请求；
- `AndroidDownloadManager` 改用 `downloadToFile` + 时长校验。

### 验证

```text
:desktopApp:compileKotlin  BUILD SUCCESSFUL
:androidApp:assembleDebug  BUILD SUCCESSFUL
```

身份 A/B 实测（同一账号）：

```
android 付费曲目 result=0，purl 有值
web     付费曲目 result=104003，purl 空
```

说明 NeriPlayer 的 `ct=11 + authst` 身份确实能提高可下载率。

### 已知问题

- Android 前台服务进度只在通知栏，页面没有实时进度；
- 服务是串行队列，没有暂停/恢复；
- Desktop 队列也是串行，但支持取消；
- 取消时 `runCatching` 可能吞掉 CancellationException，需要下一轮修正；
- 尚未做“下载全部歌单”；
- Range 续传只做了简单实现，没有 ETag/If-Range 校验。

### 最终构建/运行

```text
:desktopApp:compileKotlin  BUILD SUCCESSFUL
:androidApp:assembleDebug  BUILD SUCCESSFUL
```

- PC 端已重启（窗口 `QM-Download`）；
- Android APK 已安装并启动（平板 `HA27VL78`）；
- 首次 Android 下载会请求通知权限。

---

## 2026-11-15 · 下载失败排查（进行中）

### 新增

- 调试日志：
  - Desktop：`desktopApp/data/logs/download.log`；
  - Android：`adb logcat -s QM-Download`；
  - 记录 `VKey` 身份/档位/result/retcode/msg、下载 HTTP 状态/长度/最终字节/文件头、校验时长。
- 独立脚本 `tools/validate_qq_download.py`：
  - 自动读 Java Properties 会话；
  - Android/Web 双身份 VKey；
  - HEAD/Range/完整下载 + FLAC 音频帧/时长检查。
- 新增 `:desktopApp:probe`，用 App 自身 core 代码验证单曲。

### 实测

对之前失败的 `リパブリック讃歌`（songmid `001sNF6p25cYp3`）：

```text
android result=0 purl=yes
web     result=0 purl=yes
HEAD 200 length=9586797
first16 = 66 4c 61 43 ...
下载 audio_bytes=9578331，时长 91.96s
```

App 自身 core probe 同样得到 9,586,797 字节、`VALID`。
说明服务端与 core 链路都能拿到完整文件；8KB 文件需要从 UI/旧文件/覆盖路径继续定位。

### 注意

- 现有 8KB 文件时间戳为 18:57，早于方案 B 构建；先确认是否旧文件；
- 重新下载时应自动删除无效文件并重下；
- 如果仍出现 8KB，请提供 `download.log` 或 logcat。

---

## 2026-11-15 · 8KB 根因确定并修复

### 根因

日志显示下载和校验都成功：

```text
[Download] done bytes=9586797 head=664c6143...
[Validate] size=9586797 valid=true
```

但最终文件只有 8KB。原因是 `AudioTagWriter.writeFlac()` 在重写 FLAC 时：

- 解析并写入了 STREAMINFO/VORBIS_COMMENT 等元数据块；
- **没有把元数据之后的音频帧追加回去**；
- 结果文件只剩约 8KB 的元数据。

所以 8KB 不是 CDN 返回的占位文件，而是**本地标签写入器截断了音频**。

### 修复

`AudioTagWriter.writeFlac()` 在写完元数据块后追加：

```kotlin
output.write(bytes, offset, bytes.size - offset)
```

`offset` 是原文件元数据结束、音频帧开始的位置。

### 验证

用 App 自身 probe 下载同一首歌：

```text
FILE bytes=9586797
[Validate] valid=true
AFTER_TAG bytes=9586650
```

标签写入后仍保留 9.58MB 音频，不再变 8KB。

### PC 退出/重新登录

- 窗口关闭改为 `exitApplication()` + `exitProcess(0)`，避免 OkHttp/协程线程阻止 JVM 退出；
- 退出登录会取消下载 Job、清理二维码/错误/选中状态并删除会话文件；
- 实测 `CloseMainWindow()` 后进程退出（`still=False`）。

### 构建

```text
:core:test                 BUILD SUCCESSFUL
:desktopApp:compileKotlin  BUILD SUCCESSFUL
:androidApp:assembleDebug  BUILD SUCCESSFUL
```

PC 端已重启；Android 已重新安装。

---

## 2026-11-15 · PC 退出/重新登录按钮不可见

### 根因

`PlaylistPane` 的 `LazyColumn` 没有 `weight(1f)`，会按内容高度扩张，
把底部“退出登录”按钮挤出窗口可视区域；窗口默认尺寸也没有显式设置。

### 修复

- `LazyColumn` 加 `Modifier.weight(1f)`，只占剩余高度；
- 账号栏右上角新增常驻按钮：
  - **退出登录**
  - **关闭**
- 窗口默认尺寸改为 `1180 × 820`；
- “关闭”调用 `exitProcess(0)`，与标题栏关闭一致；
- 退出登录会取消下载、清空会话/二维码/选中状态。

### 验证

- 通过 `PrintWindow` 截图确认左上角显示“退出登录 / 关闭”；
- 窗口关闭实测进程退出（`still=False`）。

### 后续语义修正

- 用户反馈“退出”指退出登录，不是关闭应用；
- 已移除头部“关闭”按钮，只保留 **退出登录**；
- 应用退出只走标题栏 X（内部仍 `exitProcess(0)`）。

### 新增优化方案

- [`DOWNLOAD_OPTIMIZATION.md`](DOWNLOAD_OPTIMIZATION.md)：
  - 参考 aria2/Motrix/Ketch/bdownloader/NeriPlayer；
  - S1 任务持久化/暂停恢复/分享链接；
  - S2 多连接/重试/并发/限速；
  - S3 通知/任务面板/命名模板/sidecar。

### 构建

```text
:desktopApp:compileKotlin  BUILD SUCCESSFUL
```

---

## 2026-11-15 · S1：任务持久化/暂停恢复/分享链接

### core

- 新增 `DownloadTaskRecord` / `DownloadTaskStatus`；
- 新增 `FileDownloadTaskStore`：
  - JSON 持久化；
  - `load/save/upsert/remove/pending`；
  - 跨 Desktop/Android 共用。

### Desktop

- 下载前把选中歌曲写入 `data/tasks.json`；
- 下载队列从任务文件读取，支持：
  - 暂停（取消当前 Job，任务标记 PAUSED，保留 `.part`）；
  - 恢复未完成任务（PAUSED/QUEUED/DOWNLOADING 重新入队）；
  - 启动时显示“恢复未完成（N）”；
- 分享链接输入框 + “打开分享歌单”：
  - `QQMusicShareLinkParser`；
  - 打开后显示合成歌单并加载曲目；
  - 非法链接/空歌单错误提示。

### Android

- `QmApplication` 增加 `taskStore`（`filesDir/download_tasks.json`）；
- `DownloadService` 改为任务队列：
  - 新任务写入 store；
  - 暂停/继续/取消通知动作；
  - 暂停保留 `.part`；
  - 服务重启后从 store 恢复；
- 页面增加分享链接输入/打开、未完成任务“继续”入口。

### 验证

```text
:desktopApp:compileKotlin  BUILD SUCCESSFUL
:androidApp:assembleDebug  BUILD SUCCESSFUL
```

PC 已启动，Android 已安装。

### 已知问题

- Android 页面上的 pending 数量是快照，不会随服务实时刷新；
- Desktop/Android 都是串行队列；
- 失败任务不会自动重试，需要后续加“重试”入口；
- 分享链接 UI 仅编译验证，待真实链接回归；
- 多连接、限速、仅 Wi-Fi 仍属于 S2。

---

## 2026-11-15 · 适配微信/手机分享链接

### 问题

手机微信分享链接是移动端页面：

```text
https://i2.y.qq.com/n3/other/pages/details/playlist.html
  ?platform=11&appshare=android_qq&appversion=20090508
  &hosteuin=owcq7eolNeni7z**&id=9616026161&ADTAG=wxfshare&mmkey
```

歌单 ID 不在 `/playlist/{id}` 路径里，而在查询参数 `id` 中，
原解析器只认桌面路径，所以返回 null。

### 修复

`QQMusicShareLinkParser`：

- 保留 `/playlist/{id}`；
- 路径包含 `playlist` 或 `taoge` 时，读取查询参数：
  `id / disstid / dissid / tid / playlistId`；
- 只接受纯数字且长度 ≥5；
- 非歌单页面（如 `song.html?id=...`）仍拒绝；
- 域名仍限制 `*.qq.com`。

### 验证

单元测试新增：

```text
微信 playlist.html?id=9616026161  → 9616026161
taoge.html?disstid=9616026161     → 9616026161
song.html?id=...                  → null
```

真实请求：

```text
CgiGetDiss(disstid=9616026161) code=0，返回 19 首
```

### 构建

```text
:core:test                 BUILD SUCCESSFUL
:desktopApp:compileKotlin  BUILD SUCCESSFUL
:androidApp:assembleDebug  BUILD SUCCESSFUL
```

PC 已重启，Android 已安装。

---

## 2026-11-15 · S2 第一阶段：多连接分段

### 完成

- `QQMusicDownloader` 新增 `DownloadOptions`：
  - `segmented`：是否启用多连接；
  - `maxConnections`：2–4；
  - `minFileBytes`：8MB 以上才分段；
  - `maxAttempts`：4。
- 下载前 HEAD/Range 探测：
  - `Content-Length`；
  - `Accept-Ranges`；
  - HEAD 不可用时用 `Range: bytes=0-0`。
- 大文件分段：
  - 默认 2 连接；
  - 每段独立 Range + `.part.N`；
  - 支持段级续传；
  - 合并后校验总长度；
  - 任一段失败/服务端不支持 Range → 清理段文件并回退单连接。
- 重试：
  - 指数退避 1s/2s/4s/8s；
  - 最多 4 次；
  - 认证/无权限/无资源错误不重试。
- Desktop：
  - 新增“多连接加速（实验，2 连接）”开关；
  - 默认开启，可关闭回退单连接；
  - 配置写入 `config.properties`。

### 实测

App 自身 probe 下载 9.5MB FLAC：

```text
[Download] probe total=9586797 ranges=true
[Download] segmented start total=9586797 conn=2
[Download] done bytes=9586797
[Validate] valid=true
AFTER_TAG bytes=9586650
```

与单连接相比，同一文件下载时间明显缩短。

### 仍需完成

- Android 端尚未提供“加速开关”（当前默认 2 连接）；
- 并发任务队列仍为串行；
- 限速/仅 Wi-Fi 未实现；
- 分段失败回退单连接已做，但还没有“段级重试次数”统计。

---

## 2026-11-15 · 独立“下载”页

### Desktop

- 新增 `DesktopDownloadsScreen`：
  - 任务列表、状态、进度、已下载/总大小；
  - 顶部“全部暂停/全部继续”；
  - 单任务：暂停/继续/重试/目录/删除；
  - 删除只清理任务与 `.part`，不删除最终音频；
- 主界面顶部新增“下载”入口；
- 歌单页只保留“下载所选”，提交后自动切换到下载页；
- 任务进度每 500ms 写入 `tasks.json`，页面每秒刷新。

### Android

- 新增 `AndroidDownloadsScreen`；
- 顶部“音乐库 / 下载（N）”切换；
- 任务来自 `FileDownloadTaskStore`，每秒刷新；
- 服务进度回调写入任务进度；
- 全局暂停/继续、失败重试、删除任务；
- 歌单页移除“继续未完成”按钮，统一到下载页。

### 构建

```text
:desktopApp:compileKotlin  BUILD SUCCESSFUL
:androidApp:assembleDebug  BUILD SUCCESSFUL
```

PC 已重新启动，Android 已安装。

### 已知问题

- Android 单任务暂停仍是“暂停整个服务队列”；
- 失败任务需要手动重试；
- 下载页暂无速度/剩余时间；
- 并发任务队列仍未实现（当前串行）。

---

## 2026-11-15 · 下载页布局对齐

### 调整

- Android 去掉顶部单独的一行“音乐库/下载”导航；
- 把 **下载 / 退出登录** 放回账号栏，与 PC 一致；
- 分享链接输入框移到账号栏下方，按钮在输入框下方（与 PC 相同）；
- Android 下载页增加 **返回音乐库**，与 PC 一致；
- Android 退出按钮文案改为“退出登录”。

### 截图验证

- PC：账号栏 `下载 / 退出登录`，分享框在下方，歌单列表在左侧；
- Android：账号栏 `下载 / 退出登录`，分享框在下方，下载页 `返回音乐库`。

### 构建

```text
:androidApp:assembleDebug  BUILD SUCCESSFUL
```

Android 已安装，PC 已重启。

---

## 2026-11-15 · 并行任务 / 单任务暂停 / 自动重试

### 任务模型

- `DownloadTaskRecord` 新增 `retryCount`；
- 持久化兼容旧任务文件。

### 并行

- Download 页新增“并行任务 1–6”选择；
- Desktop 用 `Semaphore(maxConcurrent)` 控制并行；
- Android 服务按 `maxConcurrent` 动态调度；
- 设置分别持久化：
  - Desktop：`config.properties` 的 `maxConcurrent`；
  - Android：`qm_download_ui` 的 `maxConcurrent`；
- 修改后对后续调度生效（Android 每轮重新读取）。

### 单任务暂停/恢复

- Desktop：
  - 单任务“暂停”取消该任务 Job；
  - “继续”重新启动该任务，不取消其他并行任务；
- Android：
  - 新增 `ACTION_PAUSE_TASK / ACTION_RESUME_TASK / ACTION_REMOVE_TASK`；
  - 服务维护 `activeJobs`，只取消目标任务；
  - 下载页单任务按钮：暂停/继续/重试/删除。

### 自动重试

- 失败后自动重试最多 3 次；
- 重试间隔 1s/2s/3s；
- 认证/无权限/无资源不重试；
- 用尽后标记 FAILED，可手动重试；
- 页面显示 `重试 n/3`。

### 构建

```text
:desktopApp:compileKotlin  BUILD SUCCESSFUL
:androidApp:assembleDebug  BUILD SUCCESSFUL
```

### 验收重点

- 选 3 首歌曲 → 并行设为 3 → 三首同时进度；
- 单任务暂停后其他任务继续；
- 单任务继续只恢复该任务；
- 断网触发失败，观察自动重试次数；
- 3 次后变 FAILED，手动重试恢复。

---

## 2026-11-15 · v0.5 正式版

### 版本号

- Android：`versionCode 5`，`versionName 0.5`；
- Desktop：窗口标题 `QM-Download v0.5`，jpackage `0.5.0`。

### 产物

```text
dist/
  QM-Download-v0.5-win-x64-portable.zip
  QM-Download-v0.5-x64.msi
  QM-Download-v0.5.apk
  SHA256SUMS.txt
```

### 签名

- Android 生成 `androidApp/release.keystore`，alias `qmdownload`；
- 使用 v2 签名；
- 签名配置读取 `androidApp/keystore.properties`；
- 需备份 keystore，后续升级必须使用同一把 key。

### 桌面便携版

- `createDistributable` 生成 app-image；
- 压缩为便携 zip；
- 实测从便携目录启动，标题显示 `QM-Download v0.5`；
- `data/` 正确创建在 exe 同目录；
- 同时生成 MSI 安装包。

### 校验

- APK `aapt dump badging`：versionCode 5 / versionName 0.5；
- `apksigner verify`：v2 签名通过；
- 便携 zip 202 个条目，无 `data/`，包含 `QM-Download.exe`。

---

## 2026-11-15 · v0.5 Android R8 正式版

- Android release 开启 R8 + resource shrinking；
- 移除未使用的 `kotlinx-serialization-json`；
- 新增 `androidApp/proguard-rules.pro`；
- APK：23.4MB → 2.6MB；
- 已在平板完整回归并安装；
- Windows 便携版/MSI 保持不变；
- `dist/QM-Download-v0.5.apk` 已替换为优化版。
