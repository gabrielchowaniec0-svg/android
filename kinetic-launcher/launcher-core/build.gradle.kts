import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Launcher core (library module): home grid, installed apps, home layout,
// widget hosting and system gestures.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kinetic.core"
    compileSdk = 35
    defaultConfig { minSdk = 33 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    // AppEntry exposes ImageBitmap; HomeLayoutStore exposes StateFlow
    api(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
}
