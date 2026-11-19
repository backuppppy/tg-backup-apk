// Pure Kotlin/JVM: the MTProto client, the takeout export engine and its
// HTML/JSON writers. Kept free of Android so it can be built and tested on a
// plain JVM.
plugins {
    kotlin("jvm") version "2.4.21"
}

group = "org.tgbackup"
version = "1.0"

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Android only (the desktop copy of export-core has no D8, and no such flag).
        // D8 in AGP 8.13 knows Kotlin metadata up to 2.2: with 2.4's, it warned once per class of the ~4,500
        // generated MTProto classes (768k log lines with --stacktrace, 18/11). Metadata 2.2 reads the same.
        freeCompilerArgs.add("-Xmetadata-version=2.2.0")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    for (key in listOf("live", "step", "code", "password", "source", "ids", "probe", "samplePer", "phone", "session", "deep")) {
        systemProperty(key, System.getProperty(key) ?: "")
    }
    testLogging { showStandardStreams = true }
}
