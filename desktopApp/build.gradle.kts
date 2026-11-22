plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "moe.ouom.qmdownload.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "QM-Download"
            packageVersion = "0.6.0"
            description = "QQ Music playlist downloader"
            vendor = "QM-Download"
            copyright = "© 2026 QM-Download"
        }
        // 正式版启用 ProGuard；规则见 desktopApp/proguard-rules.pro
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
    }
}

tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "CLI probe for VKey + download path"
    mainClass.set("moe.ouom.qmdownload.desktop.ProbeCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    listOf("songmid", "mediaMid", "quality", "out", "session").forEach { key ->
        System.getProperty("qm.$key")?.let { systemProperty("qm.$key", it) }
    }
}
