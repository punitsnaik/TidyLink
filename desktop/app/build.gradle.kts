plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.google.zxing:core:3.5.3")
    // :shared declares these as implementation, so the UI needs its own
    // compile-time copies: room-runtime for TidyLinkDb's supertype,
    // serialization-json for the trash tombstone encode. Versions match :shared.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}

compose.desktop {
    application {
        mainClass = "dev.punit.tidylink.desktop.MainKt"
    }
}
