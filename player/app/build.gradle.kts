plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Apply the google-services plugin only if you've dropped google-services.json
// next to this file. That way the project still builds as a smoke-test demo
// before you've set up Firebase.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.smartech.screens"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smartech.screens"
        // minSdk lives on the per-flavor blocks below.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Backend base URL — overridable at build time.
        //   ./gradlew assembleRelease -PapiBase=https://api.smartech.group/api
        val apiBase = providers.gradleProperty("apiBase").getOrElse("https://api.example.com/api")
        buildConfigField("String", "API_BASE", "\"${apiBase}\"")

        // Join code used when registering against an org.
        val joinCode = providers.gradleProperty("joinCode").getOrElse("SMARTECH")
        buildConfigField("String", "JOIN_CODE", "\"${joinCode}\"")
    }

    // Two flavors that ship the same app at different OS floors.
    //   modern  — minSdk 26, adaptive icons, the everyday build.
    //   legacy  — minSdk 23, ships PNG mipmap icons too so it installs on
    //             Android 6 / 7 tablets with their old launchers.
    // Each flavor's APK lands at app/build/outputs/apk/<flavor>/<buildType>/
    // (e.g. app-modern-debug.apk, app-legacy-debug.apk).
    flavorDimensions += "compatibility"
    productFlavors {
        create("modern") {
            dimension = "compatibility"
            minSdk = 26
        }
        create("legacy") {
            dimension = "compatibility"
            minSdk = 23
            // Suffix the version so it's obvious in Settings → Apps which
            // build is on a given device.
            versionNameSuffix = "-legacy"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // NOTE: add signing config in a local `keystore.properties` before shipping.
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties"
        )
    }
}

dependencies {
    // Core + lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.04.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media3 ExoPlayer (the modern ExoPlayer)
    val media3 = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // Networking — Retrofit over OkHttp with kotlinx.serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // DataStore for device token / preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager for heartbeat
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:32.8.1"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
