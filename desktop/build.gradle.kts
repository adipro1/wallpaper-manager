import java.net.URI
import java.security.MessageDigest
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
    implementation(libs.jcefmaven)

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

// In development, native helpers (libmpv, yt-dlp) come from the repo's bin/ folder.
val devBinDir = rootDir.resolve("../bin").normalize()

tasks.test {
    useJUnitPlatform()
    systemProperty("wpm.bin.dir", devBinDir.absolutePath)
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("wpm.bin.dir", devBinDir.absolutePath)
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

// yt-dlp (video URLs from YouTube and other sites): pinned build, checked with SHA-256.
// Run `gradlew getYtDlp` once; sites change often, so the app can also update it later.
val ytDlpVersion = "2026.08.19"
val ytDlpSha256 = "66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a"

val getYtDlp by tasks.registering {
    group = "setup"
    description = "Downloads yt-dlp.exe into the repo's bin/ folder."
    val target = devBinDir.resolve("yt-dlp.exe")
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        val url = "https://github.com/yt-dlp/yt-dlp/releases/download/$ytDlpVersion/yt-dlp.exe"
        val bytes = URI(url).toURL().openStream().use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == ytDlpSha256) { "yt-dlp.exe checksum mismatch: $digest" }
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
        println("Installed $target")
    }
}
