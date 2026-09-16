import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.a24_hr_clock"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.a24_hr_clock"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
    }

    val fitbitClientId: String = localProperties.getProperty("FITBIT_CLIENT_ID") ?: ""
    val fitbitClientSecret: String = localProperties.getProperty("FITBIT_CLIENT_SECRET") ?: ""

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    defaultConfig {
        buildConfigField("String", "FITBIT_CLIENT_ID", "\"$fitbitClientId\"")
        buildConfigField("String", "FITBIT_CLIENT_SECRET", "\"$fitbitClientSecret\"")
        buildConfigField("String", "HEALTH_LEGACY_ACCOUNT", "\"${localProperties.getProperty("HEALTH_LEGACY_ACCOUNT", "")}\"")
        buildConfigField("String", "SLEEP_RELAY_URL", "\"${localProperties.getProperty("SLEEP_RELAY_URL", "")}\"")
        resValue("string", "google_app_id", localProperties.getProperty("FIREBASE_APP_ID", ""))
        resValue("string", "google_api_key", localProperties.getProperty("FIREBASE_API_KEY", ""))
        resValue("string", "gcm_defaultSenderId", localProperties.getProperty("FIREBASE_SENDER_ID", ""))
        resValue("string", "project_id", localProperties.getProperty("FIREBASE_PROJECT_ID", ""))
    }

    sourceSets.getByName("test").resources.srcDir(rootProject.file("fixtures"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Astronomical calculations
    implementation("org.shredzone.commons:commons-suncalc:3.9")

    // Networking & Serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.android.gms:play-services-auth:21.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // DataStore for settings and tokens
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Document management
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Browser for OAuth
    implementation("androidx.browser:browser:1.8.0")

    // Location services
    implementation(libs.androidx.work.runtime.ktx)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
