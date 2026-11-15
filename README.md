# QM-Download

QQ 音乐歌单下载器，支持 **Windows 便携版** 和 **Android**。

GitHub：<https://github.com/THEREALLCSR/QM-Download>

基于 NeriPlayer 的 QQ 音乐登录、歌单读取和下载能力重新实现，专注做一件事：把账号里的歌单稳定、完整地下载到本地。

> 当前版本：**v0.5**
>
> 发布包请从仓库的 **Releases** 页面下载。源码仓库不包含 APK / EXE / MSI 等二进制产物。

---

## 功能

### 账号与歌单

- QQ 音乐扫码登录；
- 显示账号昵称与头像；
- 读取账号创建/收藏的歌单；
- 支持单曲选择、全选、批量下载；
- 支持粘贴 QQ 音乐歌单分享链接直接打开。

支持的分享链接包括：

```text
https://y.qq.com/n/ryqq_v2/playlist/9735321048
https://y.qq.com/n/ryqq/playlist/9735321048
https://i2.y.qq.com/n3/other/pages/details/playlist.html?id=9616026161
https://y.qq.com/n/m/detail/taoge.html?disstid=9616026161
纯数字歌单 ID：9616026161
```

### 下载

- 音质选择：
  - 无损 FLAC
  - 320k
  - 128k
- 自动从高音质向低音质回退；
- 文件名遵循 QQ 音乐官方风格：

```text
{artist}-{title}.{ext}
```

- 自动写入音频标签：
  - TITLE
  - ARTIST
  - ALBUM
- 下载前校验文件完整性；
- 下载目录可自由选择。

### 下载管理

- 独立“下载”页，不和歌单操作混在一起；
- 任务持久化，重启后可以继续；
- **并行任务数 1–6**，在下载页通过下拉框选择；
- 单个任务暂停 / 继续 / 重试 / 删除；
- 暂停保留 `.part`，继续时断点续传；
- 失败自动重试 **3 次**；
- 大文件默认 2 连接分段下载，失败自动回退单连接；
- Android 前台服务通知栏控制；
- 下载完成后自动移除任务记录，文件保留。

### Windows 便携版

- 免安装，解压即用；
- 配置、登录状态、任务、日志都保存在 exe 同目录的 `data/`：

```text
QM-Download.exe
data/
  config.properties
  session.properties
  tasks.json
  logs/download.log
```

- 移动整个文件夹即可迁移；
- 同时提供 MSI 安装包（可选）。

### Android

- 原生 Android 应用，支持手机/平板；
- 宽屏自动切换左右双栏布局；
- 前台服务下载，退后台继续；
- 通知栏暂停/继续；
- 单选/全选/批量下载；
- 分享链接输入；
- 当前版本使用正式签名。

---

## 界面说明

### 音乐库

```text
[头像] 昵称        [下载] [退出登录]

[粘贴 QQ 音乐歌单链接............]
[打开分享歌单]

我的歌单
  我喜欢 (1074)
  进行曲 (39)
  ...

选择歌单
  音质  [无损] [320k] [128k]
  [✓] 多连接加速（实验，2 连接）
  [全选/取消] [选择目录]  D:\Music
  [下载所选 (N)]

  ☐ 歌曲名
    歌手 · 专辑
```

### 下载页

```text
[返回音乐库] 下载任务              [全部暂停/全部继续]
共 N 个任务；暂停后保留 .part，继续时从断点续传
并行任务 [2 个 ▼]

歌曲名
歌手 · 无损 · 下载中 · 重试 1/3
██████████░░░░  65%  6.2MB / 9.5MB
D:\Music

[暂停] [目录] [删除]
```

并行数点击后从下拉菜单选择 `1–6`。

---

## 下载与安装

### Windows

从 Releases 下载：

```text
QM-Download-v0.5-win-x64-portable.zip
```

1. 解压到任意目录；
2. 双击 `QM-Download.exe`；
3. 首次启动会在 exe 同目录创建 `data/`；
4. 在“音乐库”页选择下载目录。

也提供：

```text
QM-Download-v0.5-x64.msi
```

便携版和 MSI 目前没有商业代码签名证书，Windows 首次运行可能出现 SmartScreen 提示。

### Android

从 Releases 下载：

```text
QM-Download-v0.5.apk
```

- 最低 Android 8.0（API 28）；
- 如之前安装的是 debug 包，需要先卸载再安装正式包（签名不同）；
- 正式包签名信息见 [docs/RELEASE.md](docs/RELEASE.md)。

---

## 使用方法

### 1. 登录

1. 打开应用；
2. 点击“获取登录二维码”；
3. 使用手机 QQ 扫码；
4. 手机上确认登录。

登录成功后，应用会读取账号昵称、头像和歌单。

### 2. 下载账号歌单

1. 左侧选择歌单；
2. 右侧勾选歌曲，或点击“全选/取消”；
3. 选择音质和下载目录；
4. 点击“下载所选”；
5. 自动进入下载页查看进度。

### 3. 打开分享歌单

1. 复制 QQ 音乐歌单分享链接；
2. 粘贴到“粘贴 QQ 音乐歌单链接”；
3. 点击“打开分享歌单”；
4. 歌单加载后按普通歌单方式下载。

### 4. 下载页管理

- **全部暂停 / 全部继续**：控制整个队列；
- **单任务暂停 / 继续**：只影响当前歌曲；
- **重试**：失败任务立即重新入队，并清零自动重试计数；
- **删除**：删除任务与 `.part`，不删除已完成的最终音频；
- **并行任务**：下拉选择 1–6，影响后续调度。

### 5. 退出登录

点击账号栏的“退出登录”。

> 退出登录只清除登录状态，不会退出应用，也不会删除已经下载的音频。

---

## 构建

### 环境

- JDK 17
- Android SDK 37
- Gradle Wrapper（仓库自带）

Windows 示例：

```powershell
$env:JAVA_HOME = 'C:\Dev\jdk17'
$env:ANDROID_HOME = 'C:\Dev\Android\Sdk'
$env:GRADLE_USER_HOME = 'C:\Dev\gradle-home'
```

### Android

```powershell
.\gradlew.bat :androidApp:assembleDebug --no-daemon --console=plain
.\gradlew.bat :androidApp:assembleRelease --no-daemon --console=plain
```

Release 构建需要本地 `androidApp/keystore.properties` 与 keystore 文件；这两个文件已加入 `.gitignore`，不会提交到仓库。

### Windows

```powershell
.\gradlew.bat :desktopApp:createDistributable --no-daemon --console=plain
.\gradlew.bat :desktopApp:packageMsi --no-daemon --console=plain
```

便携版 app-image 输出：

```text
desktopApp/build/compose/binaries/main/app/QM-Download
```

MSI 输出：

```text
desktopApp/build/compose/binaries/main/msi/QM-Download-0.5.0.msi
```

### 测试

```powershell
.\gradlew.bat :core:test --no-daemon --console=plain
```

---

## 项目结构

```text
QM-Download/
  core/                 Kotlin/JVM 共享核心
    auth/               扫码登录、Cookie、g_tk
    library/            歌单读取
    playback/           VKey / 播放地址解析
    download/           下载器、校验、标签写入、任务存储
    link/               分享链接解析
  desktopApp/           Compose Desktop
  androidApp/           Android + Compose
  docs/                 设计、调试、优化、发布文档
  gradle/               Gradle Wrapper
  tools/                独立验证脚本
```

### 核心设计

- **core** 为纯 Kotlin/JVM 模块，不依赖 Android API；
- Desktop 与 Android 共用登录、歌单、VKey、下载、标签写入；
- 下载任务使用同一个 JSON 模型；
- Android 使用 SAF 写入用户选择的目录；
- Windows 使用普通文件路径写入下载目录。

---

## 文档

- [设计说明](DESIGN.md)
- [项目报告](REPORT.md)
- [调试与验证](docs/DEBUG.md)
- [QQ 下载技术分析](docs/NERIPLAYER_QQ_DOWNLOAD_TECH.md)
- [下载器优化方案](docs/DOWNLOAD_OPTIMIZATION.md)
- [体积优化分析](docs/SIZE_OPTIMIZATION.md)
- [发布说明](docs/RELEASE.md)
- [开发记录](docs/DEVLOG.md)

---

## 数据与隐私

- 登录 Cookie 只保存在本机：
  - Windows：`data/session.properties`
  - Android：应用私有目录；
- 不连接自建服务器；
- 没有遥测、统计上报或远程配置；
- 日志会避免输出 Cookie、musicKey、authst、vkey；
- `data/`、keystore、签名密码和发布产物均已加入 `.gitignore`。

请不要把自己的 `data/`、`session.properties`、keystore 或日志提交到公开仓库。

---

## 已知限制

- 只支持 QQ 音乐；
- 不支持歌词、评论、播放功能；
- 仅能下载当前账号有权限访问的歌曲；
- 会员/版权限制以 QQ 音乐服务端为准；
- 暂不支持限速、仅 Wi-Fi、实时速度/剩余时间；
- QQ 音乐接口可能变化，旧版本可能失效；
- Windows 产物未做商业代码签名。

---

## 免责声明

本项目仅用于学习、研究和下载自己账号有权访问的内容。

- 本项目与腾讯 / QQ 音乐没有关联；
- 请遵守 QQ 音乐用户协议和当地法律法规；
- 请勿将本项目用于批量抓取、传播侵权内容或商业用途；
- 因使用本项目产生的任何后果由使用者自行承担。

---

## 开源协议

本项目基于 **GNU General Public License v3.0** 发布，见 [LICENSE](LICENSE)。

QQ 音乐登录、歌单与下载能力的实现参考了 [NeriPlayer](https://github.com/cwuom/NeriPlayer)，在此致谢。

## 致谢

- [NeriPlayer](https://github.com/cwuom/NeriPlayer)
- [JetBrains Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)
- [OkHttp](https://square.github.io/okhttp/)
- [Kotlin](https://kotlinlang.org/)
