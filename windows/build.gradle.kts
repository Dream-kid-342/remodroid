import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)

    // Java bindings for Google's libwebrtc, with the Windows x64 native library.
    implementation(libs.webrtc.java)
    implementation(variantOf(libs.webrtc.java) { classifier("windows-x86_64") })
    // Win32 SendInput / GetSystemMetrics.
    implementation(libs.jna.platform)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "com.remodroid.windows.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "RemoDroid"
            // MSI versions must be numeric MAJOR.MINOR.BUILD; CI passes the tag version.
            packageVersion = (findProperty("remodroid.versionName") as String?) ?: "0.1.0"
            description = "RemoDroid remote support"
            vendor = "RemoDroid"
            modules("java.prefs", "jdk.unsupported")
            windows {
                iconFile.set(project.file("icons/remodroid.ico"))
                menuGroup = "RemoDroid"
                shortcut = true
                upgradeUuid = "6f0d5d55-2f43-4a8e-9b39-3b7f6f0c9a51"
            }
        }
    }
}

/** Headless end-to-end media check: ./gradlew :windows:smokeController --args="<url> <id> <password> [level] [seconds]" */
tasks.register<JavaExec>("smokeController") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.remodroid.windows.tools.SmokeControllerKt")
}

/** Local Windows capture -> encode -> decode loopback: ./gradlew :windows:smokeLoopback --args="5" */
tasks.register<JavaExec>("smokeLoopback") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.remodroid.windows.tools.SmokeLoopbackKt")
}
