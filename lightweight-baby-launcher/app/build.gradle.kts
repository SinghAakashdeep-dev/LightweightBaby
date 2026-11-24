plugins {
    id("com.android.application")
}

android {
    namespace = "com.fuelstride.app"
    compileSdk = 35

    defaultConfig {
        // Same id as the earlier Fuel & Stride APK, so this installs over it as an update
        applicationId = "com.fuelstride.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
