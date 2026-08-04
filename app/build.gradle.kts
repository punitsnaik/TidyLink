import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Optional release signing. Create keystore.properties (git-ignored) next to
// settings.gradle.kts with:
//   storeFile=/absolute/path/to/release.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
//
// storeFile may be absolute or relative to the repo root; prefer an absolute
// path pointing OUTSIDE the repo, so the signing key can never be committed by
// an accidental `git add -f` or swept up in a zip of the project folder.
//
// Without keystore.properties, release builds fall back to the DEBUG key (see
// buildTypes.release below) and warn loudly.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "dev.punit.tidylink"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.punit.tidylink"
        // Android 10. Two things stop being guaranteed below API 31/34, and both
        // are handled rather than assumed - don't "simplify" either back:
        //   - Material You dynamic color is API 31+. TidyLinkTheme falls back to
        //     the Ocean/Coral palettes below that.
        //   - dataExtractionRules is API 31+. res/xml/backup_rules.xml carries
        //     the same API-key exclusion for API 29–30, which read
        //     fullBackupContent instead.
        // Also: SQLite gained ALTER TABLE DROP COLUMN in 3.35 (API 34), so a
        // future migration must not use it. See MIGRATION_3_4.
        minSdk = 29
        targetSdk = 36
        // versionCode is what Android compares to decide "is this an upgrade?"
        // Every shipped versionCode is burned: it must increase on every
        // release and can never be reused or lowered - a reused versionCode
        // reads as "not an upgrade", so installs of the old APK are stranded
        // with no path forward. versionName is the cosmetic string users see;
        // keep it in step with the v* git tag that triggers release.yml.
        versionCode = 8
        versionName = "1.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Fall back to the debug key so `assembleRelease` still yields
                // an installable APK for local testing - but never let that
                // happen silently. A debug-signed APK carries the universally
                // known android/android key: anyone can forge an update for it,
                // and installs can never be upgraded by the real key afterwards.
                // Tagged releases cannot reach this branch - release.yml writes
                // keystore.properties and then verifies the signer isn't debug.
                signingConfig = signingConfigs.getByName("debug")
                project.logger.warn(
                    buildString {
                        appendLine()
                        appendLine("+----------------------------------------------------------------+")
                        appendLine("|  WARNING: keystore.properties not found.                       |")
                        appendLine("|  The release APK will be signed with the DEBUG key.            |")
                        appendLine("|                                                                |")
                        appendLine("|  Fine for local testing. DO NOT DISTRIBUTE this APK.           |")
                        appendLine("|  Real releases are built by CI on a v* tag (release.yml).      |")
                        appendLine("+----------------------------------------------------------------+")
                    }
                )
            }
            // R8 shrinking/obfuscation. The keep rules in proguard-rules.pro
            // cover the reflection-heavy libs (Room, Retrofit,
            // kotlinx-serialization, WorkManager).
            //
            // NOTE: proguardFiles() is the ONLY way to hand rules to R8. An
            // earlier version of this file kept them in src/main/keepRules/,
            // which is not an AGP source-set convention - R8 never read them
            // and silently stripped every kotlinx-serialization $$serializer,
            // breaking AI classification and JSON import/export in release
            // builds only. Always smoke-test a release build (save → scrape →
            // classify → export/import) after touching dependencies.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    sourceSets {
        // Room's MigrationTestHelper loads the exported schema JSON from the
        // test APK's assets, so the schemas directory has to be on the
        // androidTest source set. Nothing ships in the app APK.
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

ksp {
    // Export Room schemas so migrations can be written and verified.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)

    // Lifecycle / ViewModel / Coroutines
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Room (local database with FTS)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging (large libraries stay smooth)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Scraping
    implementation(libs.jsoup)

    // Networking (LLM API)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    // NOTE: deliberately no okhttp logging-interceptor - it would log
    // Authorization headers (the user's API keys) if ever wired in.
    implementation(libs.kotlinx.serialization.json)

    // Background retry queue + Custom Tabs
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.browser)

    // Images
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Frosted glass (backdrop blur)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    testImplementation(libs.junit)

    // Room migration tests - instrumented, because ALTER TABLE DROP COLUMN
    // (MIGRATION_3_4) needs SQLite 3.35+ and Robolectric bundles 3.32.2.
    // Run with: ./gradlew connectedDebugAndroidTest (needs a device/emulator).
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
