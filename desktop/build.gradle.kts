import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.compose") version "1.7.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
}

val pcVersion = System.getenv("PC_VERSION") ?: "1.0.0"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

compose.desktop {
    application {
        mainClass = "stalker.MainKt"
        jvmArgs += listOf("-Dstalker.version=$pcVersion", "-Dsun.java2d.uiScale.enabled=true", "-Xmx768m")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Stalker"
            packageVersion = pcVersion
            description = "Reproductor FLAC bit-perfect"
            vendor = "Stalker"
            copyright = "Software libre · sin anuncios"
            includeAllModules = true
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "Stalker"
                shortcut = true
                menu = true
                perUserInstall = true
                dirChooser = false
                upgradeUuid = "6f1c2b7e-5a4d-4e0b-9c41-8d2f7a3e9b10"
            }
        }
    }
}
