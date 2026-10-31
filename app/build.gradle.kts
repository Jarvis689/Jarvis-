plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.manish.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.manish.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
