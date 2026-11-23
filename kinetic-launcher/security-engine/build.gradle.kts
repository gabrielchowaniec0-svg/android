import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Icon/label customization + private workspace manager (library module).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.kinetic.security"
    compileSdk = 35
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.security.crypto)
    // StateFlow is part of the public API, so consumers need coroutines too
    api(libs.kotlinx.coroutines.android)
}
