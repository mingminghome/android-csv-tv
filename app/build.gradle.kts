plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Add necessary imports for Properties and File handling
import java.util.Properties
import java.io.File

android {
    namespace = "com.mmhw.csvtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mmhw.csvtv"
        minSdk = 21
        targetSdk = 35
        versionCode = 3
        versionName = "1.03"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Load sheetsApiKey and defaultSheetId from local.properties
        val localProperties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { inputStream ->
                localProperties.load(inputStream)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // Media3 for ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.0")
    implementation("androidx.media3:media3-ui:1.4.0")
    implementation("androidx.media3:media3-datasource:1.4.0")
    implementation("androidx.media3:media3-datasource-rtmp:1.4.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.0")

    // Glide for image loading
    implementation(libs.glide)

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