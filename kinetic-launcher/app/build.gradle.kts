import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The runnable application module. Android Studio's "app" run configuration
// targets this; it pulls in every feature module below.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

// Version = number of commits, so builds from Android Studio and from GitHub agree
// (and the in-app updater can tell which is newer).
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)

android {
    namespace = "com.kinetic.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kinetic.launcher"
        minSdk = 33          // AGSL / RuntimeShader require Android 13 (API 33)
        targetSdk = 35
        versionCode = 1000 + commitCount
        versionName = "build ${1000 + commitCount}"
    }

    // GitHub's release build signs with your own key (a repository secret), so its
    // updates install over the version Android Studio put on your phone.
    val ciKeystore = System.getenv("KINETIC_KEYSTORE_FILE")
    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("KINETIC_KEYSTORE_PASSWORD")?.ifBlank { null } ?: "android"
                keyAlias = System.getenv("KINETIC_KEY_ALIAS")?.ifBlank { null } ?: "androiddebugkey"
                keyPassword = System.getenv("KINETIC_KEY_PASSWORD")?.ifBlank { null } ?: "android"
            }
        }
    }

    buildTypes {
        // "release" runs much faster than "debug" (no debugger hooks, fully compiled,
        // Compose's speed profiles installed). It's signed with the same debug key so
        // Android Studio can install it with Run: Build ▸ Select Build Variant ▸ release.
        release {
            // R8 shrinks and optimises the release build (smaller, faster to start).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ciKeystore != null) "ci" else "debug")
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

    // S Pen handwriting on the home screen (runs on the phone; the ~20 MB English model downloads once).
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
    // Plane spotter camera view.
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // Installs the pre-compiled start-up profile on your phone (release builds).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}
