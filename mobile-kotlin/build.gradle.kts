plugins {
    id("com.android.application") version "8.8.0"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

// Android Module Configuration for Nuri's Offline-First POS & 21st.dev Brutalist Compose UI
android {
    namespace = "com.ban.foodintelligence.pos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ban.foodintelligence.pos"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-avatar"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "tr", "de")
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Jetpack Compose (21st.dev Brutalist Terminal UI)
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Room SQLite Database for Offline Outbox
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // WorkManager for background sync queue
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Security & Cryptography
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking (Retrofit/OkHttp)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Edge AI Inference: ONNX Runtime for Offline Yield Prediction (Zero-Network MITM Protection)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
}
