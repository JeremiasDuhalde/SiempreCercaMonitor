plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Credenciales leidas desde gradle.properties (no en el source code)
val monitorEmail: String by project
val monitorPassword: String by project
val webhookSecret: String by project
val keystorePassword: String by project

android {
    namespace = "com.siemprecerca.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.siemprecerca.monitor"
        minSdk = 26
        targetSdk = 36
        versionCode = 34
        versionName = "3.5.1"

        // Inyectar credenciales via BuildConfig (no hardcodeadas en source)
        buildConfigField("String", "BASE_URL", "\"https://app.siemprecercasrl.net\"")
        buildConfigField("String", "MONITOR_EMAIL", "\"$monitorEmail\"")
        buildConfigField("String", "MONITOR_PASSWORD", "\"$monitorPassword\"")
        buildConfigField("String", "WEBHOOK_SECRET", "\"$webhookSecret\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("siemprecerca-release.jks")
            storePassword = keystorePassword
            keyAlias = "siemprecerca"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Android core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    // Flic 2 SDK
    implementation("com.github.50ButtonsEach:flic2lib-android:1.3.1")
}
