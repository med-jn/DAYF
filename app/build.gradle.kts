import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single source of truth for the version: version.properties.
// Every build that produces an app (assemble / install / bundle) raises versionCode by one automatically,
// so each installed build has a new number. build.ps1 / build.sh pass -PskipBump because they bump it themselves.
val versionFile = rootProject.file("version.properties")
val versionProps = Properties().apply { versionFile.inputStream().use { load(it) } }
val isAppBuild = gradle.startParameter.taskNames.any { n ->
    listOf("assemble", "install", "bundle").any { n.contains(it, ignoreCase = true) }
}
var vCode = (versionProps.getProperty("versionCode") ?: "1").trim().toInt()
if (isAppBuild && !project.hasProperty("skipBump")) {
    vCode += 1
    versionProps.setProperty("versionCode", vCode.toString())
    versionFile.outputStream().use { versionProps.store(it, null) }
}

// Play upload key: from keystore.properties (local, git-ignored) or from environment variables (CI).
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun upload(key: String, env: String): String? = ksProps.getProperty(key) ?: System.getenv(env)
val uploadPath: String? = ksProps.getProperty("storeFile")?.let { rootProject.file(it).absolutePath }
    ?: System.getenv("UPLOAD_KEYSTORE_PATH")

android {
    namespace = "app.dayf"
    compileSdk = 34

    defaultConfig {
        // the public host of the invitation page (web/ folder, deployed on Cloudflare Pages); override in gradle.properties
        val shareHost = (project.findProperty("shareHost") as String?) ?: "tik.orcaup.com"
        manifestPlaceholders["shareHost"] = shareHost
        resValue("string", "share_host", shareHost)
        applicationId = "com.tikguest"
        minSdk = 29
        targetSdk = 34
        versionCode = vCode
        versionName = "1.0.$vCode"
    }

    signingConfigs {
        // Fixed key for sideload builds: every APK has the same signature, so updates install over each other.
        create("dev") {
            storeFile = file("tikguest-dev.keystore")
            storePassword = "tikguest"
            keyAlias = "tikguest"
            keyPassword = "tikguest"
        }
        if (uploadPath != null) {
            create("upload") {
                storeFile = file(uploadPath)
                storePassword = upload("storePassword", "UPLOAD_STORE_PASSWORD")
                keyAlias = upload("keyAlias", "UPLOAD_KEY_ALIAS")
                keyPassword = upload("keyPassword", "UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (uploadPath != null) signingConfigs.getByName("upload") else signingConfigs.getByName("dev")
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
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
