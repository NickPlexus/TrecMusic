// build.gradle.kts (:app)
//
// НАЗНАЧЕНИЕ: Конфигурация сборки модуля приложения.
//
// ИЗМЕНЕНИЯ:
// 1. Добавлен id("kotlin-parcelize") - критично для работы TrecTrackEnhanced.
// 2. Включен coreLibraryDesugaring - для поддержки Java 8 Time API на старых Android (если понадобится).
// 3. packaging { resources { excludes ... } } - стандартный фикс для конфликтов META-INF при сборке APK.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.trec.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.trec.music"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "onnx"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    // Icons
    implementation(libs.androidx.material.icons.extended)

    // --- Media3 (Player) ---
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    // --- Utils ---
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.documentfile)

    // Coil (Image Loading)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)

    // Splash Screen
    implementation(libs.androidx.core.splashscreen)

    // Local storage
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // ONNX Runtime (AI source separation / karaoke)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
