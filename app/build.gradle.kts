plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Play upload key: supplied only by the release workflow through environment variables.
val uploadKeystore: String? = System.getenv("UPLOAD_KEYSTORE_PATH")

android {
    namespace = "app.dayf"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dayf.ai"
        minSdk = 29
        targetSdk = 34
        // Always increases with every CI run, so each build can update the previous one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        // Fixed key => every APK has the same signature => updates install over each other.
        create("dev") {
            storeFile = file("tikguest-dev.keystore")
            storePassword = "tikguest"
            keyAlias = "tikguest"
            keyPassword = "tikguest"
        }
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (uploadKeystore != null) signingConfigs.getByName("upload") else signingConfigs.getByName("dev")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
