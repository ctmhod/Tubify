plugins {
    id("com.android.application")
}

android {
    namespace = "com.tubify.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tubify.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:159.0.20261102144711")
}
