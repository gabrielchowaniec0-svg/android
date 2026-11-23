// app/build.gradle.kts — the dependencies and config these modules need.
// If you split into :launcher-core, :wallpaper-engine, :lockscreen-transition,
// :security-engine, put Compose + the shared deps in each module.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") // Kotlin 2.0+ Compose compiler
}

android {
    namespace = "com.kinetic.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kinetic.launcher"
        minSdk = 33          // AGSL / RuntimeShader require API 33 (Android 13)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Cinematic unlock -> system biometric
    implementation("androidx.biometric:biometric:1.1.0")

    // Encrypted storage for the owner's customization/private-space settings
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
