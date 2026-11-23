plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aynthor.simulator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.droids.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 67
        versionName = "2.9.7-pre"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("DROIDS_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("DROIDS_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("DROIDS_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("DROIDS_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            // Uses standard local Android debug signing when building locally
        }
        release {
            // Keep R8 minification disabled: VirtualDisplayUserService relies on reflection
            // into hidden Android DisplayManager / InputManager / ActivityTaskManager APIs.
            isMinifyEnabled = false
            val ksPath = System.getenv("DROIDS_KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Shizuku API & Provider (v13.1.5)
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
}
