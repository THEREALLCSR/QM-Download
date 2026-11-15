# QM-Download R8 rules
# 业务代码没有反射；保留 Manifest 入口由 AGP 自动处理。
# OkHttp / Compose / Kotlin 均自带 consumer rules。

# org.json 由 core 的 JVM 依赖提供；Android 侧也保留，避免不同构建组合下被裁掉。
-keep class org.json.** { *; }

# 保留任务模型字段名，便于任务 JSON 升级时排查。
-keepclassmembers class moe.ouom.qmdownload.core.download.DownloadTaskRecord {
    <fields>;
}
