import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("org.jetbrains.compose") version "1.8.2"
}

// CI run number → every build installs as an update over the previous one.
val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
version = "1.0.$build"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.json:json:20250517")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
}

compose.desktop {
    application {
        mainClass = "com.titanvps.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Titan VPS"
            packageVersion = version.toString()
            description = "Titan VPS"
            vendor = "Titan VPS"
            // xray.exe + geoip.dat/geosite.dat (downloaded in CI into resources/windows).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            // jdk.localedata: Russian month names ("13 июля 2027", not "13 Jul 2027").
            modules("java.naming", "jdk.crypto.ec", "jdk.unsupported", "java.management", "jdk.localedata")
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "Titan VPS"
                shortcut = true
                dirChooser = false
                perUserInstall = true
                // Fixed, so a new installer upgrades the installed app.
                upgradeUuid = "6f3b2a8e-4c1d-4e57-9a0b-7d2e5c9f1a34"
            }
        }
    }
}
