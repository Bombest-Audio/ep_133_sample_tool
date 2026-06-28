plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ep133.sampletool"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ep133.sampletool"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    // Release signing material is supplied at build time via environment variables
    // (CI) or -P gradle properties (local) — nothing secret lives in the repo:
    //   EP133_KEYSTORE_FILE, EP133_KEYSTORE_PASSWORD, EP133_KEY_ALIAS, EP133_KEY_PASSWORD
    // When the keystore is absent/missing the "release" signingConfig is never created,
    // the release build is produced UNSIGNED, and local/dev + debug builds never fail.
    val releaseStoreFile = (System.getenv("EP133_KEYSTORE_FILE")
        ?: project.findProperty("EP133_KEYSTORE_FILE") as String?)
        ?.let { file(it) }
        ?.takeIf { it.exists() }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = System.getenv("EP133_KEYSTORE_PASSWORD")
                    ?: project.findProperty("EP133_KEYSTORE_PASSWORD") as String?
                keyAlias = System.getenv("EP133_KEY_ALIAS")
                    ?: project.findProperty("EP133_KEY_ALIAS") as String?
                keyPassword = System.getenv("EP133_KEY_PASSWORD")
                    ?: project.findProperty("EP133_KEY_PASSWORD") as String?
                enableV1Signing = true   // jar signature — older sideload readers
                enableV2Signing = true   // primary, required
                enableV3Signing = true   // key-rotation capable
            }
        }
    }

    buildTypes {
        release {
            // First signed release ships with minify OFF to decouple signing from R8 risk.
            // proguard-rules.pro is committed and ready; flip this to true in a follow-up
            // release once the keep-rules are proven on real hardware.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // null when no keystore is configured (local/dev) → unsigned release, not a failure
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        prefab = true   // enables Oboe AAR prefab headers for CMake
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // Don't compress WASM, .pak, or .hmls files in the APK — WebView loads them directly
    androidResources {
        noCompress += listOf("wasm", "pak", "hmls", "woff", "otf")
    }

    testOptions {
        unitTests {
            // Return default values (0 / null / false) for unmocked Android APIs such as
            // android.util.Log, instead of throwing RuntimeException. Required for unit
            // tests that call through real MIDIRepository paths that contain Log.d/Log.w.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Compose BOM — single version source for all Compose libs
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Compose integration
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Core AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // WebView (kept for sample management fallback)
    implementation("androidx.webkit:webkit:1.9.0")

    // Custom Tabs — opens TE firmware updater in-app via Chrome (Wave 3 wires the intent)
    implementation("androidx.browser:browser:1.8.0")

    // JSON parsing for EP-133 data
    implementation("org.json:json:20231013")

    // Oboe — low-latency audio via AAudio (NDK)
    implementation("com.google.oboe:oboe:1.9.3")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Instrumented / E2E tests
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Copy web assets from the shared data/ directory into assets/data/ before build
tasks.register<Copy>("copyWebAssets") {
    from("${rootProject.projectDir}/../data")
    into("${projectDir}/src/main/assets/data")
}

// Copy the shared MIDI polyfill into assets/data/ so the WebView can load it
tasks.register<Copy>("copyPolyfill") {
    from("${rootProject.projectDir}/../shared/MIDIBridgePolyfill.js")
    into("${projectDir}/src/main/assets/data")
}

// Copy EP-133 protocol data into assets for Compose screens
tasks.register<Copy>("copyEP133Data") {
    from("${rootProject.projectDir}/../shared") {
        include("ep133-*.json")
    }
    into("${projectDir}/src/main/assets")
}

tasks.named("preBuild") {
    dependsOn("copyWebAssets", "copyPolyfill", "copyEP133Data")
}
