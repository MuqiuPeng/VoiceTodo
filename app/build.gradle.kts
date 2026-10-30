plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

android {
    namespace = "app.voicetodo"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.voicetodo"
        minSdk = 26
        targetSdk = 36
        // The release tag is v<versionName>; the in-app updater compares
        // against it. versionCode follows from it so the two cannot drift.
        val version = "0.11.3"
        versionName = version
        versionCode = version.split('.').map(String::toInt).let { (a, b, c) -> a * 10000 + b * 100 + c }

        // Phones (and the Apple-silicon emulator) are arm64; the other ABIs
        // in the sherpa-onnx AAR would add ~35 MB for devices nobody has.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Updates install only over an APK signed with the same key, so releases
    // must never fall back to the per-machine debug key. The keystore and its
    // password live outside the repo, in ~/.gradle/gradle.properties.
    val keystore = providers.gradleProperty("VOICETODO_KEYSTORE").orNull
    val keystorePassword = providers.gradleProperty("VOICETODO_KEYSTORE_PASSWORD").orNull
    signingConfigs {
        if (keystore != null && keystorePassword != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = keystorePassword
                keyAlias = "voicetodo"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":parser"))
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    // Per-app language (中文 / English) on every Android version, not just 13+.
    implementation("androidx.appcompat:appcompat:1.7.1")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
