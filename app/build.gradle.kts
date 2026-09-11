plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.termidroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.termidroid"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "0.4.0"

        // Include 4 ABIs so bootstrap can pick the matching proot/Alpine at runtime.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
}
