// Standalone desktop build - deliberately decoupled from the Android build
// at the repo root. Invoked with the root repo's Gradle wrapper (../gradlew).
plugins {
    kotlin("jvm") version "2.2.10" apply false
    kotlin("plugin.serialization") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
