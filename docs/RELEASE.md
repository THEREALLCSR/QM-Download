# QM-Download v0.6 发布记录

## 产物

| 文件 | 说明 |
|---|---|
| `QM-Download-v0.6-win-x64-portable.zip` | Windows 免安装便携版；解压后运行 `QM-Download.exe` |
| `QM-Download-v0.6-x64.msi` | Windows 安装包（可选） |
| `QM-Download-v0.6.apk` | Android 正式签名 APK（R8 优化） |
| `SHA256SUMS.txt` | 上述文件的 SHA-256 |

## 版本内容

### 新增

- **歌单批次下载**：一次提交 = 一个批次，文件写入 `<下载目录>/<歌单名>/`；
- 下载页按批次分组，支持整批暂停 / 继续 / 重试 / 打开目录 / 删除（不删已下载音频）；
- **全局下载限速**：不限 / 512KB/s / 1MB/s / 2MB/s，运行中切换立即生效并持久化；
- **登录态自动续签**：登录时服务端下发的 `refresh_token` 用于到期前续签，减少重新扫码；
- **登录过期自动暂停**：下载中登录过期时先尝试续签，失败则暂停整个队列（不标失败），重新登录后自动继续。

### 修复

- **歌单全选上限 99 首**：分页加载完整歌单，支持整歌单下载（P0）；
- 并行任务数调节在下载过程中不生效（改为动态调度）；
- 批次完成进度恒为 0（改用"总数 − 剩余"口径）；
- 下载上千首时通过 Intent 传递数据触发 Binder 1MB 上限；
- Android 端续签与 Desktop 行为不一致（统一为 QmApplication 单一入口 + Mutex）。

### 其他

- 任务记录新增 `batchId` / `subDir` / `seq` / `pausedByAuth`，旧任务数据向后兼容；
- README 更正开源能力来源说明（QQ 音乐源主要参考 MeloX-Android）。

## Windows 便携版

```text
QM-Download-v0.6-win-x64-portable/
  QM-Download.exe
  app/
  runtime/
  README.md
```

- 无需安装；
- 首次启动后在 `QM-Download.exe` 同目录创建 `data/`：
  - `data/session.properties`
  - `data/config.properties`
  - `data/tasks.json`
  - `data/batches.json`
  - `data/logs/download.log`
- 移动整个文件夹即可迁移；
- 下载目录在“音乐库”页选择。

## Android

- `versionCode = 6`；
- `versionName = 0.6`；
- 使用 `androidApp/release.keystore` 正式签名；
- **R8 优化版**：`isMinifyEnabled = true`、`isShrinkResources = true`；
- 从 v0.5 正式版可直接覆盖升级（同一签名）；若装的是 debug 包，需先卸载：
  ```text
  adb uninstall moe.ouom.qmdownload
  adb install QM-Download-v0.6.apk
  ```
- 卸载会清除旧登录状态与任务记录。

## 签名

- Keystore：`androidApp/release.keystore`
- Alias：见 `androidApp/keystore.properties`
- 密码：见 `androidApp/keystore.properties`
- **必须备份 keystore 与密码**，否则后续版本无法覆盖升级。

## 构建命令

```powershell
$env:JAVA_HOME='C:\Dev\jdk17'
$env:ANDROID_HOME='C:\Dev\Android\Sdk'
$env:GRADLE_USER_HOME='C:\Dev\gradle-home'

.\gradlew.bat :androidApp:assembleRelease --no-daemon --console=plain
.\gradlew.bat :desktopApp:packageReleaseDistributable --no-daemon --console=plain
.\gradlew.bat :desktopApp:packageReleaseMsi --no-daemon --console=plain
```

产物位置：

```text
androidApp/build/outputs/apk/release/androidApp-release.apk
desktopApp/build/compose/binaries/main-release/app/QM-Download
desktopApp/build/compose/binaries/main-release/msi/QM-Download-0.6.0.msi
```

## 已知限制

- 继续扫码登录需用户手动操作（续签只能延长，不能永久免登录）；
- 应用长期不打开导致 Cookie 彻底过期时仍需重新扫码；
- 登录态明文保存在本机（`session.properties` / SharedPreferences），请勿外传 `data/` 目录；
- 微信扫码登录的续签字段与 QQ 授权不同，当前版本仅覆盖 QQ 扫码路径；
- Android 单任务暂停是取消该任务 Job；并行任务中的其他任务不受影响；
- 自动重试仅针对网络/临时错误，无权限等错误不重试。
