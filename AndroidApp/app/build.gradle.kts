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
    }

    buildTypes {
        release {
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // Don't compress WASM, .pak, or .hmls files in the APK — WebView loads them directly
    androidResources {
        noCompress += listOf("wasm", "pak", "hmls", "woff", "otf")
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

    // JSON parsing for EP-133 data
    implementation("org.json:json:20231013")

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
