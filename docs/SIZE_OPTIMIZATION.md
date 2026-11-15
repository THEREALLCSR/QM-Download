# 体积优化分析（v0.5）

## 结论

- **Android 有非常大的优化空间**：当前正式 APK 23.4MB，其中约 22.8MB 是两个未压缩的 `classes.dex`。
- 开启 R8（`isMinifyEnabled` + `isShrinkResources`）后，实测 APK 为 **2.6MB**，约减少 **89%**。
- **Desktop/Windows 的体积不是业务代码造成的**：app 约 51MB，JRE runtime 约 65MB；业务 jar + core jar 合计不到 0.4MB。
- 删除未使用的 `kotlinx-serialization-json` 只节省约 0.7MB（桌面 app 内），对整体影响很小。
- ABI 裁剪对当前 APK 意义不大：`lib/` 下只有约 40KB 的 `libandroidx.graphics.path.so`。

## APK 构成

未优化 v0.5：

```text
classes.dex       13.57 MB
classes2.dex       9.22 MB
resources.arsc     0.45 MB
其他               0.15 MB
----------------------------
总计              23.4 MB
```

优化后：

```text
classes.dex        2.30 MB
resources.arsc     0.09 MB
PublicSuffix       0.13 MB
其他               0.08 MB
----------------------------
总计               2.6 MB
```

### 已做修改

1. `androidApp/build.gradle.kts`：
   - `isMinifyEnabled = true`
   - `isShrinkResources = true`
   - 使用 `proguard-android-optimize.txt`
2. 新增 `androidApp/proguard-rules.pro`：
   - 保留 `org.json`
   - 保留 `DownloadTaskRecord` 字段（便于任务 JSON 兼容）
3. `core` 移除未使用的 `kotlinx-serialization-json` 依赖。
4. 已构建并安装 minified release 预览包，启动正常，无 `FATAL EXCEPTION`。

## Windows 构成

```text
app/                     51.13 MB
  skiko-windows-x64.dll  13.46 MB
  icudtl.dat              9.98 MB
  Compose jars            ~20 MB
runtime/                 64.80 MB
  lib/modules            43.19 MB
  bin/server/*           12.11 MB
业务 jar（desktop+core）  0.34 MB
```

### 可做但收益有限

- 删除未使用的 `kotlinx-serialization-json`：约 0.7MB；
- 排除部分 Compose tooling/重复生命周期 jar：约 1–2MB，需回归测试；
- 自定义 jlink 裁剪/压缩 runtime：可能节省 10–15MB，但构建复杂；
- 对桌面 app 做 R8/ProGuard：潜在收益 10–20MB，但 Compose/Skiko 反射兼容风险高，不建议在 v0.5 上冒险。

### 不建议

- 裁剪 ABI：当前 native 库总量极小，收益接近 0；
- 删除业务代码：`ProbeCli` 只在 desktop jar 中，约 0.17MB；删掉对 APK 无影响，对桌面收益也很小。

## 建议

1. 已采用 R8 优化 APK，并完成登录、歌单、分享链接、并行下载、暂停恢复回归。
2. Windows 保持现有便携版；若需要显著减小，再单独做 jlink 裁剪。
3. 正式 APK 已替换为优化版本：

```text
dist/QM-Download-v0.5.apk
2.6 MB
SHA-256: E8E08A66F63BF605E6105D71079368357BBE34AAC01C465CA301EB6159BB7084
```

Windows 两个产物保持原样，未重新打包。
