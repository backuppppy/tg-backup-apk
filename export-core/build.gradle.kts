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
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    for (key in listOf("live", "step", "code", "password", "source", "ids", "probe")) {
        systemProperty(key, System.getProperty(key) ?: "")
    }
    testLogging { showStandardStreams = true }
}
