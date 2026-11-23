import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generates Kinetic's Baseline Profile on your phone (see docs/SPEED.md):
// Android Studio ▸ Gradle ▸ app ▸ Tasks ▸ other ▸ generateBaselineProfile.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.kinetic.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 33
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

baselineProfile {
    // Use the phone plugged into your computer.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
