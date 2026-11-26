plugins {
    id("com.android.application")
}

android {
    namespace = "com.tubify.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tubify.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 32
        versionName = "0.1.31"
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
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // libcrashhelper/libcrashtools stay in: GeckoView's merged manifest
            // declares its crash-handler service, and without the libs the
            // service dies on load, looping "Tubify keeps stopping" dialogs
            // while the app keeps working.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// GeckoView 161 is compiled against API 37 while AGP 8.13 supports 36;
// the AAR metadata check is advisory, disable it for this project.
afterEvaluate {
    tasks.matching { it.name == "checkReleaseAarMetadata" }.configureEach {
        enabled = false
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:161.0.20261118180411")
}
