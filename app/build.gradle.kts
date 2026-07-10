plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

// Add necessary imports for Properties and File handling
import java.util.Properties
import java.io.File

android {
    namespace = "com.mmhw.csvtv"
    compileSdk = 35

    // Load configurations from local.properties
    val localProperties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { inputStream ->
            localProperties.load(inputStream)
        }
    }

    defaultConfig {
        applicationId = "com.mmhw.csvtv"
        minSdk = 21
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                ?: localProperties.getProperty("keystore.password")
            keyAlias = localProperties.getProperty("keystore.alias") ?: "csvtv"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                ?: localProperties.getProperty("keystore.keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Added to resolve META-INF/DEPENDENCIES conflict
    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Jetpack Compose dependencies
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling.preview) // For Compose preview in debug builds

    // Leanback for TV UI
    implementation(libs.androidx.leanback)
    implementation("androidx.cardview:cardview:1.0.0")

    // Media3 for ExoPlayer (aligned with Jellyfin FFmpeg decoder build)
    val media3 = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-datasource-rtmp:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // Prebuilt FFmpeg audio (MPEG-L2/MP2, AC3, DTS, etc.) — many IPTV TS streams need this.
    // GPL-3.0 (matches this project). Provides androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    // Glide for image loading
    implementation(libs.glide)
    implementation(libs.glideOkhttp)
    // Glide compiler (annotation processor) for @GlideModule
    kapt(libs.glideCompiler)

    // OkHttp for fetching CSV data
    implementation(libs.okhttp)

    // Jsoup for web scraping (if needed)
    implementation("org.jsoup:jsoup:1.17.2")
    implementation(libs.opencsv)

    // AppCompat for SetupActivity
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}