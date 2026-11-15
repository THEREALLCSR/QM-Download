# QM-Download v0.5 发布记录

## 产物

| 文件 | 说明 |
|---|---|
| `QM-Download-v0.5-win-x64-portable.zip` | Windows 免安装便携版；解压后运行 `QM-Download.exe` |
| `QM-Download-v0.5-x64.msi` | Windows 安装包（可选） |
| `QM-Download-v0.5.apk` | Android 正式签名 APK（R8 优化，约 2.6MB） |
| `SHA256SUMS.txt` | 上述三个文件的 SHA-256 |

## Windows 便携版

```text
QM-Download-v0.5-win-x64-portable/
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
  - `data/logs/download.log`
- 移动整个文件夹即可迁移；
- 下载目录在“音乐库”页选择。

## Android

- `versionCode = 5`；
- `versionName = 0.5`；
- 使用 `androidApp/release.keystore` 正式签名（v2）；
- **R8 优化版**：`isMinifyEnabled = true`、`isShrinkResources = true`；
- APK 从 23.4MB 降至约 2.6MB；
- 如果平板还装着旧 debug 包，需要先卸载再安装正式包（签名不同）：
  ```text
  adb uninstall moe.ouom.qmdownload
  adb install QM-Download-v0.5.apk
  ```
- 卸载会清除旧登录状态与任务记录。

## 签名

- Keystore：`androidApp/release.keystore`
- Alias：`qmdownload`
- 密码：见 `androidApp/keystore.properties`
- **必须备份 keystore 与密码**，否则后续版本无法覆盖升级。

## 构建命令

```powershell
$env:JAVA_HOME='C:\Dev\jdk17'
$env:ANDROID_HOME='C:\Dev\Android\Sdk'
$env:GRADLE_USER_HOME='C:\Dev\gradle-home'

.\gradlew.bat :androidApp:assembleRelease --no-daemon --console=plain
.\gradlew.bat :desktopApp:createDistributable --no-daemon --console=plain
.\gradlew.bat :desktopApp:packageMsi --no-daemon --console=plain
```

产物位置：

```text
androidApp/build/outputs/apk/release/androidApp-release.apk
desktopApp/build/compose/binaries/main/app/QM-Download
desktopApp/build/compose/binaries/main/msi/QM-Download-0.5.0.msi
```

## 版本内容

- QQ 音乐扫码登录、歌单、分享链接；
- 文件名 `{artist}-{title}.{ext}`；
- FLAC/MP3 标签写入；
- 任务持久化、暂停/恢复；
- 独立下载页；
- 大文件 2 连接分段下载；
- 并行任务 1–6（下拉选择）；
- 单任务暂停/恢复；
- 失败自动重试 3 次。

## 已知限制

- 暂无限速、仅 Wi-Fi、实时速度/剩余时间；
- Android 单任务暂停是取消该任务 Job；并行任务中的其他任务不受影响；
- 自动重试仅针对网络/临时错误，认证过期/无权限不重试。
