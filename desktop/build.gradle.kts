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
val appVersion = "3.3.1"
version = appVersion

kotlin {
    jvmToolchain(21)
}

// ONNX Runtime (AI depth maps) ships native code for every OS (~50 MB); keep only Windows x64.
val onnxRuntime by configurations.creating { isTransitive = false }
val onnxRuntimeWindows by tasks.registering(Jar::class) {
    from({ onnxRuntime.map { zipTree(it) } }) {
        exclude("ai/onnxruntime/native/linux-*/**", "ai/onnxruntime/native/osx-*/**", "ai/onnxruntime/native/win-aarch64/**")
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
    archiveFileName.set("onnxruntime-win-x64.jar")
    destinationDirectory.set(layout.buildDirectory.dir("onnxruntime"))
}

dependencies {
    onnxRuntime(libs.onnxruntime)
    implementation(files(onnxRuntimeWindows))
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

// The native helpers ship in the app folder's bin\ (found through compose.application.resources.dir).
val appResources = layout.buildDirectory.dir("app-resources")
val prepareNativeHelpers by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Copies libmpv-2.dll and yt-dlp.exe from the repo's bin/ folder into the app resources."
    from(devBinDir) { include("libmpv-2.dll", "yt-dlp.exe") }
    into(appResources.map { it.dir("common/bin") })
    doFirst {
        listOf("libmpv-2.dll", "yt-dlp.exe").forEach {
            check(devBinDir.resolve(it).exists()) { "bin/$it is missing: run `gradlew getLibmpv getYtDlp` first" }
        }
    }
}

compose.desktop {
    application {
        mainClass = "wpm.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "WallpaperManager"
            packageVersion = appVersion
            vendor = "adipro1"
            description = "Wallpaper Manager"
            // From `gradlew suggestRuntimeModules`, plus what reflection and TLS need (HTTPS, JNA, JCEF).
            modules(
                "java.instrument", "java.net.http", "java.sql", "jdk.unsupported",
                "jdk.crypto.ec", "jdk.charsets", "jdk.zipfs", "jdk.accessibility",
            )
            appResourcesRootDir.set(appResources)
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                perUserInstall = true
                menuGroup = "Wallpaper Manager"
                upgradeUuid = "6f1a3c2e-8d4b-4f5a-9c7e-2b1d0e9f8a71"
            }
        }
    }
}

afterEvaluate {
    tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareNativeHelpers) }
}

val distributableDir = layout.buildDirectory.dir("compose/binaries/main/app/WallpaperManager")
val distDir = rootDir.resolve("../dist").normalize()

val zipDistributable by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the app folder: dist/WallpaperManager-<version>-win64.zip."
    dependsOn("createDistributable")
    from(distributableDir) { into("WallpaperManager") }
    from(rootDir.resolve("../packaging/READ ME FIRST.txt")) { into("WallpaperManager") }
    archiveFileName.set("WallpaperManager-$appVersion-win64.zip")
    destinationDirectory.set(distDir)
}

/** Inno Setup's compiler: on PATH or in its usual install folders. */
fun findIscc(): String = (System.getenv("PATH") ?: "").split(File.pathSeparator).map { File(it, "ISCC.exe") }
    .plus(listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")?.let { "$it/Programs" })
        .map { File(it, "Inno Setup 6/ISCC.exe") })
    .firstOrNull { it.exists() }?.absolutePath ?: error("Inno Setup 6 (ISCC.exe) not found: https://jrsoftware.org/isinfo.php")

val installer by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds dist/WallpaperManager-<version>-setup.exe with Inno Setup 6."
    dependsOn("createDistributable")
    workingDir = rootDir.resolve("../packaging")
    doFirst { executable = findIscc() }
    args(
        "/DAppVersion=$appVersion",
        "/DSourceDir=${distributableDir.get().asFile.absolutePath}",
        "/DOutputDir=$distDir", "WallpaperManager.iss",
    )
}

// libmpv (video engine): pinned community build, checked with SHA-256, unpacked with Windows' tar.exe.
val libmpvArchive = "mpv-dev-x86_64-20260921-git-e76a35ec95.7z"
val libmpvSha256 = "361392d64a231830ac79d1c0f91ddb68c6a709accd282b2c87b13889d382fdec"
val libmpvUrls = listOf(
    "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260921/$libmpvArchive",
    "https://github.com/adipro1/wallpaper-manager/releases/download/libmpv-20260921/$libmpvArchive",
)

val getLibmpv by tasks.registering {
    group = "setup"
    description = "Downloads libmpv-2.dll into the repo's bin/ folder."
    val target = devBinDir.resolve("libmpv-2.dll")
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        val bytes = libmpvUrls.firstNotNullOfOrNull { url ->
            runCatching { URI(url).toURL().openStream().use { it.readBytes() } }.getOrNull()?.takeIf { data ->
                MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) } == libmpvSha256
            }
        } ?: error("Couldn't download libmpv with the expected checksum")
        val temp = temporaryDir.resolve("mpv-dev.7z").apply { writeBytes(bytes) }
        val tar = File(System.getenv("SystemRoot") ?: "C:/Windows", "System32/tar.exe")
        exec { commandLine(tar.absolutePath, "-xf", temp.absolutePath, "-C", temporaryDir.absolutePath, "libmpv-2.dll") }
        target.parentFile.mkdirs()
        temporaryDir.resolve("libmpv-2.dll").copyTo(target, overwrite = true)
        println("Installed $target")
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
