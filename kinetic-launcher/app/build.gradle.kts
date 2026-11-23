import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The runnable application module. Android Studio's "app" run configuration
// targets this; it pulls in every feature module below.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kinetic.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kinetic.launcher"
        minSdk = 33          // AGSL / RuntimeShader require Android 13 (API 33)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

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
    implementation(project(":launcher-core"))
    implementation(project(":wallpaper-engine"))
    implementation(project(":lockscreen-transition"))
    implementation(project(":security-engine"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
