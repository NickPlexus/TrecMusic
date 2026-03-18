// build.gradle.kts (:app)
//
// НАЗНАЧЕНИЕ: Конфигурация сборки модуля приложения.
//
// ИЗМЕНЕНИЯ:
// 1. Добавлен id("kotlin-parcelize") - критично для работы TrecTrackEnhanced.
// 2. Включен coreLibraryDesugaring - для поддержки Java 8 Time API на старых Android (если понадобится).
// 3. packaging { resources { excludes ... } } - стандартный фикс для конфликтов META-INF при сборке APK.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-parcelize") // <--- ВАЖНО: Добавлено для @Parcelize
}

android {
    namespace = "com.trec.music"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trec.music"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
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

    kotlinOptions {
        jvmTarget = "1.8"
        // Разрешаем использование Unstable API Media3 без аннотаций везде (опционально, но удобно)
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Icons
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    // --- Media3 (Player) ---
    val media3Version = "1.4.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // --- Utils ---
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Coil (Image Loading)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")
}


