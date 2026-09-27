plugins {
    id("com.android.application")
}

android {
    namespace = "com.fire119.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fire119.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-hybrid"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime:2.10.0")
}
