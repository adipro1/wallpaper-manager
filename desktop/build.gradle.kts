import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

// Single source of the app version (CI checks that a vX.Y.Z tag matches it).
val appVersion = "2.0.0"
version = appVersion

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

// Expose the version to the app without hard-coding it twice.
val generateAppInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/appinfo")
    inputs.property("version", appVersion)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("wpm/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package wpm\n\ninternal const val BUILD_VERSION = \"$appVersion\"\n")
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateAppInfo)

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "wpm.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "WallpaperManager"
            packageVersion = appVersion
            vendor = "adipro1"
            modules("java.sql", "jdk.unsupported")
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                perUserInstall = true
                menuGroup = "Wallpaper Manager"
                upgradeUuid = "6f1a3c2e-8d4b-4f5a-9c7e-2b1d0e9f8a71"
            }
        }
    }
}
