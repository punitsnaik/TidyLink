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
    implementation("com.google.zxing:core:3.5.3")
}

compose.desktop {
    application {
        mainClass = "dev.punit.tidylink.desktop.MainKt"
    }
}
