import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProp(name: String) = "\"${localProps.getProperty(name, "")}\""

android {
    namespace = "com.jim.arrtv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jim.arrtv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DEFAULT_RADARR_URL", localProp("arr.radarrUrl"))
        buildConfigField("String", "DEFAULT_RADARR_KEY", localProp("arr.radarrKey"))
        buildConfigField("String", "DEFAULT_SONARR_URL", localProp("arr.sonarrUrl"))
        buildConfigField("String", "DEFAULT_SONARR_KEY", localProp("arr.sonarrKey"))
        buildConfigField("String", "DEFAULT_TMDB_KEY", localProp("arr.tmdbKey"))
    }

    buildTypes {
        // The app is only ever sideloaded onto Jim's TV, so release builds reuse the debug signing key.
        // Release is the build to install: R8 makes Compose noticeably smoother than the debug build.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // FilterChip and friends are still marked experimental in tv-material 1.0.
        freeCompilerArgs += "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
