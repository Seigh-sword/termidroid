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
        versionCode = 5
        versionName = "0.3.0"

        ndk {
            abiFilters += listOf("arm64-v8a")  // primary target for v0.3
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
