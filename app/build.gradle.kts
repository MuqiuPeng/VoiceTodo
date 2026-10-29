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
        versionCode = 1
        versionName = "0.1.0"

        // Phones (and the Apple-silicon emulator) are arm64; the other ABIs
        // in the sherpa-onnx AAR would add ~35 MB for devices nobody has.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The recognizer reads the model straight out of the APK; stored
        // uncompressed it is mapped rather than inflated on every launch.
        noCompress += "onnx"
    }
}

dependencies {
    implementation(project(":parser"))
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
