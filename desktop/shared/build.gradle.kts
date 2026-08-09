plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.room:room-runtime:2.8.4")
    // 2.6.2, not the plan's 2.6.1 pin: room-runtime 2.8.4 requires sqlite 2.6.2
    // transitively, so 2.6.1 was upgraded anyway - declared to match resolution.
    implementation("androidx.sqlite:sqlite-bundled:2.6.2")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jmdns:jmdns:3.5.9")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
