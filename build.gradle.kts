plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}

kotlin { jvmToolchain(21) }

// Things that are only for this computer's own build (the ABC station logos): private/ isn't in
// git, and a public build (-Ppublic=true) leaves it out even when it's there.
if (project.findProperty("public") != "true" && file("private").isDirectory) sourceSets["main"].resources.srcDir("private")

dependencies {
    implementation(compose.desktop.currentOs)
    // The alpha, as on the phone: Material 3 Expressive (its spring motion) is public from here.
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation(compose.materialIconsExtended)
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    // GNOME's media controls and the keyboard's media keys (MPRIS, over D-Bus).
    implementation("com.github.hypfvieh:dbus-java-core:5.1.1")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.1.1")
    implementation("org.slf4j:slf4j-nop:2.0.17")  // dbus-java logs through SLF4J; nothing to say to the user
}

// The version: 1.0 at the first packaged commit, then 1.0.N (N commits later), as the phone apps do.
val commits = providers.exec { commandLine("git", "rev-list", "--count", "HEAD"); isIgnoreExitValue = true }.standardOutput.asText.get().trim().toIntOrNull() ?: 0
val firstRelease = 1
val appVersion = if (commits <= firstRelease) "1.0.0" else "1.0.${commits - firstRelease}"

compose.desktop {
    application {
        mainClass = "com.cappyjune.muse.desktop.MainKt"
        // For naming the window's class "muse", so the dock matches it to its launcher (see Main).
        jvmArgs += listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED", "-Dmuse.version=$appVersion")
        nativeDistributions {
            packageName = "muSE"
            packageVersion = appVersion
            description = "Radio, podcasts, Jellyfin music and your own"
            vendor = "cappy"
            // What the bundled Java needs beyond the basics (jdk.unsupported for Compose, the rest for OkHttp/JNA).
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "jdk.security.auth")
        }
    }
}

// Developer tool: draw the UI off-screen to a PNG (see spike/Render.kt).
tasks.register<JavaExec>("render") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.cappyjune.muse.desktop.spike.RenderKt")
    args = (project.findProperty("out") as String? ?: "build/render.png").let { listOf(it) + ((project.findProperty("size") as String?)?.split("x") ?: listOf("1400", "900")) } +
        listOfNotNull(project.findProperty("video") as String?)
    // -Psection=LIBRARY, -Ppage=<item id>|search, -Psettle=<seconds to wait for the server>
    // A throwaway home for data, so test runs don't touch the real muSE's: -Pscratch=<dir>
    (project.findProperty("scratch") as String?)?.let { environment("XDG_DATA_HOME", "$it/data"); environment("XDG_CONFIG_HOME", "$it/config") }
    listOf("section", "play", "np", "settle", "podcast", "episode", "localplay", "link").forEach { k -> (project.findProperty(k) as String?)?.let { systemProperty("muse.$k", it) } }
}



// Developer tool: the launcher icon PNGs for the package (see spike/Icon.kt).
tasks.register<JavaExec>("icon") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.cappyjune.muse.desktop.spike.IconKt")
    args = listOf("packaging/icons")
}

// For packaging/package.sh.
tasks.register("printVersion") { doLast { println(appVersion) } }
