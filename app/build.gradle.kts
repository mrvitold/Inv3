import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    kotlin("kapt")
    kotlin("plugin.serialization") version "1.9.25"
}

// Load keystore properties if keystore.properties exists

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.vitol.inv3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vitol.inv3"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("SUPABASE_URL") ?: ""}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("SUPABASE_ANON_KEY") ?: ""}\"")
        buildConfigField("String", "AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT", "\"${project.findProperty("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT") ?: ""}\"")
        buildConfigField("String", "AZURE_DOCUMENT_INTELLIGENCE_API_KEY", "\"${project.findProperty("AZURE_DOCUMENT_INTELLIGENCE_API_KEY") ?: ""}\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"${project.findProperty("GOOGLE_OAUTH_CLIENT_ID") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                val storeFileStr = keystoreProperties["storeFile"] as String? ?: ""
                // Resolve path relative to project root, not app module
                storeFile = if (storeFileStr.isNotEmpty()) {
                    rootProject.file(storeFileStr)
                } else {
                    null
                }
                storePassword = keystoreProperties["storePassword"] as String? ?: ""
                keyAlias = keystoreProperties["keyAlias"] as String? ?: ""
                keyPassword = keystoreProperties["keyPassword"] as String? ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only apply signing config if keystore.properties exists
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    kapt("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:1.3.4")

    // ML Kit Text Recognition v2 (Latin)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coil for images
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Timber
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.6.0"))
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Supabase (Kotlin client) - use BOM to manage versions
    implementation(platform("io.github.jan-tennert.supabase:bom:2.5.0"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:gotrue-kt") // Supabase Auth
    implementation("io.github.jan-tennert.supabase:functions-kt") // Supabase Edge Functions
    // Ktor HTTP client engine for Android (required by Supabase)
    implementation("io.ktor:ktor-client-android:2.3.12")
    
    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Serialization (compatible with Kotlin 1.9.25)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Apache POI for Excel export (full version for XSSF support)
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    // Azure Document Intelligence - REST API
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

kapt {
    correctErrorTypes = true
}