# 下载排查与验证

> 适用版本：方案 B（VKey 双身份 + Range 下载）  
> 日志不记录完整 Cookie / musicKey / authst / vkey，可安全发给维护者。

---

## 1. Desktop 日志

路径：

```text
QM-Download/desktopApp/data/logs/download.log
```

打包后（免安装版）：

```text
<QM-Download.exe 所在目录>/data/logs/download.log
```

日志示例：

```text
[19:46:43][App] desktop started log=...
[19:47:01][VKey] resolve songMid=... mediaMid=... quality=lossless ...
[19:47:02][VKey] resp android/F000 code=0 result=0 retcode=0 msg=...
[19:47:02][VKey] hit android/F000 url=https://isure.stream.qqmusic.qq.com/F000....flac
[19:47:02][Download] start target=... partBytes=0 url=...
[19:47:03][Download] response code=200 range=null contentLength=9586797 offset=0
[19:47:10][Download] done target=... bytes=9586797 head=664c6143...
[19:47:10][Validate] file=... size=9586797 ext=flac expectedMs=... valid=true
```

重点看：

- `VKey resp`：`result`、`retcode`、`msg`；
- `Download response`：`contentLength` 是否远小于预期；
- `Download done`：`bytes` 与 `head`（`664c6143` = `fLaC`）；
- `Validate`：`valid=false` 的原因。

---

## 2. Android 日志

```bash
adb logcat -s QM-Download
```

或把日志保存：

```bash
adb logcat -s QM-Download > qm-download.log
```

重点标签：

- `VKey`：身份/档位/result；
- `Download`：HTTP 状态、Content-Length、最终字节数；
- `Validate`：文件大小/时长/是否有效；
- `Service`：后台服务开始、跳过、失败原因。

---

## 3. 独立验证脚本

脚本不依赖 App，直接使用会话文件验证服务端：

```bash
cd QM-Download

# 直接指定 songmid + media_mid
python tools/validate_qq_download.py \
  --songmid 001sNF6p25cYp3 \
  --media-mid 002bwhOz3JDXCS

# 按歌名在账号歌单中查找
python tools/validate_qq_download.py --title "リパブリック讃歌"

# 完整下载并检查 FLAC 音频帧/时长
python tools/validate_qq_download.py \
  --songmid 001sNF6p25cYp3 \
  --media-mid 002bwhOz3JDXCS \
  --download --out verify.flac
```

### 用 App 自身 core 代码验证（不含 UI）

```powershell
cd QM-Download
.\gradlew.bat :desktopApp:probe `
  -Dqm.songmid=001sNF6p25cYp3 `
  -Dqm.mediaMid=002bwhOz3JDXCS `
  -Dqm.session=D:\...\desktopApp\data\session.properties `
  -Dqm.out=C:\temp\probe.flac
```

它会走与 App 完全相同的 `QQMusicUrlResolver` + `QQMusicDownloader`，
并打印 `VKey/Download/Validate` 日志。

输出重点：

- `[android]` / `[web]` 的 `req_code/result/retcode/purl`；
- `HEAD` 的真实 `Content-Length`；
- `first16`（`66 4c 61 43` = `fLaC`）；
- FLAC `audio_bytes`：必须 > 0；
- 文件大小接近 `Content-Length`。

对 `リパブリック讃歌` 的实测结果：

```text
android result=0 purl=yes
web     result=0 purl=yes
HEAD 200 length=9586797
first16 = 66 4c 61 43 ...
下载后 audio_bytes=9578331
```

说明服务端给的是完整 9.5MB FLAC，不是 8KB 占位文件。

---

## 4. 8KB 占位文件的判定

> 2026-11-15 已定位一次真实案例：下载/校验日志显示 9.5MB 且 valid=true，
> 但最终文件 8KB。根因是 `AudioTagWriter.writeFlac()` 漏写音频帧，**不是 CDN 占位**。
> 该问题已修复；如果再次出现 8KB，先看日志 `Download done bytes=` 是多少。

8KB 文件通常：

- 文件头是合法 `fLaC`；
- 元数据块后 **audio_bytes = 0**；
- 没有音频帧。

用脚本下载并检查：

```bash
python tools/validate_qq_download.py --songmid ... --download
```

如果 `audio_bytes=0`，说明下载内容确实是占位文件；如果 `audio_bytes` 很大，说明客户端保存/覆盖环节有问题。

---

## 5. 常见对比

| 现象 | 可能原因 |
|---|---|
| 脚本 HEAD 9MB，App 得到 8KB | App 读取了旧文件，或下载中断后留下了旧目标文件 |
| 脚本 result=104003，App 也失败 | 账号确实无该曲目/音质权限 |
| 脚本 purl 为空，App 也失败 | 接口/权益变化 |
| 脚本有 purl，App 日志显示 no purl | App 的 Cookie/身份/参数不一致 |
| 日志 `Download done bytes=8xxx` | CDN 返回了占位文件；检查 `VKey resp` 的 `msg` |
| 日志 `Validate valid=false` | 文件是占位/损坏；不要保留 |

---

## 6. 注意

- 脚本会读取 `desktopApp/data/session.properties`，不会打印 Cookie；
- 不要把 `session.properties` 提交到仓库；
- 付费/VIP 无权限时，脚本会明确显示 `result=104003` 或 purl 为空；
- 测试时优先用 `--download` 检查真实音频帧，而不只是 HTTP 200。
