# QM-Download v0.5

QQ 音乐歌单下载器，支持 Windows 便携版与 Android。

## 功能

- QQ 音乐扫码登录、昵称/头像、账号歌单；
- 粘贴歌单分享链接打开；
- 无损 FLAC / 320k / 128k，高音质自动回退；
- 文件名 `{artist}-{title}.{ext}`；
- 写入 TITLE / ARTIST / ALBUM 标签；
- 独立下载页；
- 并行任务 1–6（下拉选择）；
- 单任务暂停 / 继续 / 重试 / 删除；
- 失败自动重试 3 次；
- 大文件 2 连接分段下载，失败回退单连接；
- 任务持久化，重启可恢复。

## 产物

| 文件 | 说明 |
|---|---|
| `QM-Download-v0.5-win-x64-portable.zip` | Windows 免安装便携版 |
| `QM-Download-v0.5-x64.msi` | Windows 安装包（可选） |
| `QM-Download-v0.5.apk` | Android 正式签名 APK（R8 优化，约 2.6MB） |
| `SHA256SUMS.txt` | SHA-256 校验 |

## Windows

解压便携版后运行 `QM-Download.exe`。首次启动会在 exe 同目录创建 `data/`：

```text
data/config.properties
data/session.properties
data/tasks.json
data/logs/download.log
```

移动整个文件夹即可迁移。

## Android

- 最低 Android 8.0（API 28）；
- 使用正式 keystore 签名；
- 如果安装过旧 debug 包，需先卸载再安装（签名不同）。

## 已知限制

- 仅支持 QQ 音乐；
- 不包含歌词、评论、播放功能；
- 仅能下载账号有权限访问的内容；
- 暂不支持限速、仅 Wi-Fi、实时速度/剩余时间；
- Windows 产物未做商业代码签名。

## 许可证

GPL-3.0。QQ 音乐能力实现参考 NeriPlayer。
