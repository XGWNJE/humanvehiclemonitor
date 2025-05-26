plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" // Updated to match classpath version
}

android {
    namespace = "com.xgnwje.humanvehiclemonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xgwnje.humanvehiclemonitor"
        minSdk = 24 // As per your plan, for low-end devices and MediaPipe requirements
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    // composeOptions { // This block is removed as it's typically inferred or set globally
    //     kotlinCompilerExtensionVersion = "1.5.14" // Ensure this matches your Kotlin and Compose versions if re-enabled
    // }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // Required for TFLite models loaded from assets
    aaptOptions {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("androidx.navigation:navigation-compose:2.7.7") // 请检查并使用最新稳定版
    // Core Android & Kotlin
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")

    // Jetpack Compose
    implementation("androidx.activity:activity-compose:1.9.0")
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.material:material-icons-core") // 用于 Settings, PlayArrow 等
    implementation("androidx.compose.material:material-icons-extended") // 用于 CameraAlt 等更多图标
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // CameraX
    val cameraxVersion = "1.3.3" // 检查最新的稳定版本
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}") // 如果在 AndroidView 中需要 PreviewView

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4") // TensorFlow Lite Task Library for Vision
    // 使用 GPU Delegate Plugin 来确保所有必要的本地库被包含
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")
    // implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1") // 这个会被上面的 plugin 依赖自动引入，通常不需要单独声明

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Accompanist Permissions (for easier permission handling in Compose)
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")


    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.05.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
