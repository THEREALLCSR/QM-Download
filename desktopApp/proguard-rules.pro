# Desktop 正式版 ProGuard 规则
#
# okhttp 在运行时按需探测 Conscrypt / OpenJSSE / BouncyCastle 等可选安全提供者，
# 编译期这些类并不存在，ProGuard 会报 unresolved reference 并中止构建。
# 这些引用是动态探测的，缺失时 okhttp 会自动回退，安全忽略。

-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# okhttp 的 GraalVM native-image 集成类，普通 JVM 发行版不存在，安全忽略
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**

# 保留应用自身类名：主类由 jpackage 按名称启动，混淆会导致启动失败；
# 任务/批次模型含 org.json 手工映射，保留字段名更稳妥。
-keep class moe.ouom.qmdownload.** { *; }
-keepclassmembers class moe.ouom.qmdownload.** { *; }

# Compose Desktop 入口
-keep class moe.ouom.qmdownload.desktop.MainKt {
    public static void main(java.lang.String[]);
}
