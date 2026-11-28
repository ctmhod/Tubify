plugins {
    id("com.android.application")
}

android {
    namespace = "com.tubify.lite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tubify.lite"
        minSdk = 29
        targetSdk = 36
        versionCode = 59
        versionName = "0.1.59"
        resourceConfigurations.add("en")
    }

    signingConfigs {
        create("release") {
            val store = System.getenv("TUBIFY_STORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("TUBIFY_STORE_PASSWORD")
                keyAlias = "tubify"
                keyPassword = System.getenv("TUBIFY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (System.getenv("TUBIFY_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
