plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

val verifyCarlinkLibraries by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/carlink/import_libraries.py", "--verify")
    inputs.files(rootProject.file("tools/carlink/libraries.json"),
        rootProject.file("tools/carlink/import_libraries.py"))
    inputs.dir("src/main/jniLibs/arm64-v8a")
}
tasks.named("preBuild") { dependsOn(verifyCarlinkLibraries) }

android {
    namespace = "com.cabin"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    // Keep the installed application identity so existing data and upgrades remain valid.
    val ownerApplicationId = "zeno.carlink"

    defaultConfig {
        applicationId = ownerApplicationId
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        minSdk = 27
        targetSdk = 36
        versionCode = providers.environmentVariable("CABIN_VERSION_CODE").orNull?.toInt() ?: 1018
        versionName = providers.environmentVariable("CABIN_VERSION_NAME").orNull ?: "0.1.7"
        buildConfigField("boolean", "TEYES_CLUSTER_MEDIA_BRIDGE", "true")
        val sentryDsn = providers.environmentVariable("CABIN_SENTRY_DSN").orElse("").get()
        require(sentryDsn.isEmpty() || sentryDsn.matches(Regex("https://[A-Za-z0-9._~:/@%-]+"))) { "Invalid CABIN_SENTRY_DSN" }
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        val mappingUuid = providers.environmentVariable("CABIN_SENTRY_MAPPING_UUID").orElse("").get()
        require(mappingUuid.isEmpty() || mappingUuid.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid mapping UUID" }
        buildConfigField("String", "SENTRY_MAPPING_UUID", "\"$mappingUuid\"")
        manifestPlaceholders["clusterIconAuthority"] = "$ownerApplicationId.teyes.ClusterIconContentProvider"
        buildConfigField("String", "CLUSTER_ICON_AUTHORITY", "\"$ownerApplicationId.teyes.ClusterIconContentProvider\"")
        manifestPlaceholders["automotiveFeatureRequired"] = "false"
        manifestPlaceholders["templatesHostFeatureRequired"] = "false"


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        providers.environmentVariable("CABIN_DEBUG_KEYSTORE_PATH").orNull?.let { path ->
            getByName("debug") {
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (providers.environmentVariable("CABIN_KEYSTORE_PATH").isPresent) {
            create("cabinRelease") {
                storeFile = file(providers.environmentVariable("CABIN_KEYSTORE_PATH").get())
                storePassword = providers.environmentVariable("CABIN_KEYSTORE_PASSWORD").get()
                keyAlias = "cabin"
                keyPassword = providers.environmentVariable("CABIN_KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            ndk { debugSymbolLevel = "FULL" }
            signingConfig = signingConfigs.findByName("cabinRelease")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true  // Enable BuildConfig generation for debug checks
        aidl = true         // INaviVideoSink / INaviVideoSource for ClusterHomeDisplay AltVideo (0x2C)
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("libcps_7862.so", "libcarplay_plugin_r14g.so", "libaaudio_l.so",
                "libblinkAEC.so", "libusb.so", "libcrypto.so", "libmdnssd-client.so", "libopus.so",
                "libtinyalsa.so", "libstdc++.so").map { "**/$it" }
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Suppress DiscouragedApi warning for scheduleAtFixedRate usage.
        // Tested alternatives (coroutines, scheduleWithFixedDelay) caused issues
        // with microphone timing - Timer.scheduleAtFixedRate works reliably.
        // See documents/revisions.txt [19], [21] for history.
        disable += "DiscouragedApi"
        disable += "InvalidUsesTagAttribute"  // "navigation" is valid for Car App Library nav apps
    }
}

// Kotlin compiler: report deprecations and unchecked casts as warnings
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
        allWarningsAsErrors.set(false) // report but don't fail — tighten later
    }
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(true) // report only on first run — fix incrementally
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/detekt.yml"))
    baseline = file("$rootDir/detekt-baseline.xml")
    ignoreFailures = true // report only on first run
}

dependencies {
    // Offline firmware research and comparison fixtures only; excluded from every APK.
    testImplementation("org.smali:dexlib2:2.5.2")
    implementation(project(":diagnostics"))
    implementation("io.sentry:sentry-android-core:8.56.0")
    implementation("io.sentry:sentry-android-ndk:8.56.0")
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // DataStore for preferences persistence
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // DocumentFile for SAF file operations (capture recording)
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // MediaSession for AAOS integration — Media3 1.10.0 (latest stable, 2026-03-26).
    // media3-session supersedes legacy androidx.media:media (MediaSessionCompat, deprecated
    // in androidx.media 1.8.0-alpha01). GM AAOS observers use platform android.media.session.*
    // APIs which Media3 auto-registers under the hood for backwards compatibility, so the
    // GMCarMediaService → ClusterService → cluster pipeline keeps working.
    // media3-common provides Player / SimpleBasePlayer / MediaItem / MediaMetadata.
    // media3-exoplayer is intentionally NOT included: this app does not decode/play audio
    // locally — the connected phone plays over USB; we only mirror metadata + forward commands.
    val media3Version = "1.10.0"
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Car App Library for AAOS cluster navigation (Templates Host)
    implementation("androidx.car.app:app:1.7.0")
    // Cabin targets Android 8.1+ head units; omit the API-29 Automotive host Activity.

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
