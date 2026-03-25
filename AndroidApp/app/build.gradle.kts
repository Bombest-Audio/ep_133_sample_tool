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
        versionCode = 1
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // Don't compress WASM, .pak, or .hmls files in the APK — WebView loads them directly
    androidResources {
        noCompress += listOf("wasm", "pak", "hmls", "woff", "otf")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("com.google.android.material:material:1.11.0")
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

tasks.named("preBuild") {
    dependsOn("copyWebAssets", "copyPolyfill")
}
