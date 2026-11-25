import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGSL live wallpaper (library module). Its manifest registers the
// WallpaperService, which merges into :app automatically.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    namespace = "com.kinetic.wallpaper"
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
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.annotation)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
