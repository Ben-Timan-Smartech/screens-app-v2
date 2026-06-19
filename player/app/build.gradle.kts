plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Apply the google-services plugin only if you've dropped google-services.json
// next to this file. That way the project still builds as a smoke-test demo
// before you've set up Firebase.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Single source of truth for the app version: the VERSION file at the repo
// root, written by scripts/release.sh and consumed by GitHub Actions. We
// derive versionCode deterministically so APKs always sort correctly in
// Android's installer (newer > older). MAJOR*10000 + MINOR*100 + PATCH —
// works up to 99.99.99, after which we'd revisit (we won't).
val appVersionName: String = file("$rootDir/../VERSION").takeIf { it.exists() }
    ?.readText()
    ?.trim()
    ?: "0.0.0"
val appVersionCode: Int = appVersionName.split("-").first().split(".").let { parts ->
    val (major, minor, patch) = (parts + listOf("0", "0", "0")).take(3).map { it.toIntOrNull() ?: 0 }
    major * 10_000 + minor * 100 + patch
}

android {
    namespace = "com.smartech.screens"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smartech.screens"
        // minSdk lives on the per-flavor blocks below.
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        // v0.1.43: ship only English string resources. Drops the
        // localised string bundles Material3 + other AndroidX libs
        // pull in (de/es/fr/it/ja/pt/ru/zh/…). Worth a few hundred
        // KB on the APK.
        //
        // NOTE: density filters were removed here. AAPT2 errors out
        // ("Cannot filter assets for multiple densities using SDK
        // build tools 21 or later") when more than one density token
        // is in resConfigs — the modern Android approach is APK or
        // bundle splits, which we don't use. Density-bucketed PNG
        // resources are a tiny slice of the APK anyway; the locale
        // filter does the real work.
        resourceConfigurations += listOf("en")
        // v0.1.41: route vector drawables through the support library on
        // pre-API-21 codepaths. Cheap insurance for the legacy flavor.
        vectorDrawables.useSupportLibrary = true

        // Backend base URL — overridable at build time.
        //   ./gradlew assembleRelease -PapiBase=https://api.smartech.group/api
        // Default is the production custom domain mapped at Cloud Run.
        // Convention: the value ends in `/api` (matches Retrofit's baseUrl
        // and the DeviceApi path declarations). OnboardingScreen and the
        // admin Reinitialize field strip the `/api` suffix when prefilling
        // their bare-URL inputs. Demo mode (PlayerRepository.DemoMode)
        // auto-disables whenever this doesn't contain "example.com".
        val apiBase = providers.gradleProperty("apiBase").getOrElse(
            "https://screens.smartechworld.com/api"
        )
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
            // v0.1.43: density-bucket filters were removed here (see
            // defaultConfig). AAPT2 rejects multi-density resConfigs in
            // single-APK builds. The legacy build's PNG mipmaps in
            // src/legacy/res/mipmap-* still ship at their natural
            // densities; Android picks the bucket per device at runtime.
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Release signing. CI writes a base64-decoded keystore to
    //   player/app/build/release-keystore.jks
    // and exposes the alias + passwords as env vars when the
    // KEYSTORE_B64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
    // secrets are configured. Without those, release builds fall back
    // to debug signing — APKs still install via adb, but signatures
    // aren't stable across CI runners, so in-app updates won't work
    // until proper release signing is configured.
    val releaseKeystore = file("build/release-keystore.jks").takeIf { it.exists() }
    val hasReleaseSigning =
        releaseKeystore != null && System.getenv("KEYSTORE_PASSWORD") != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "screens"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
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
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // Fallback so `assembleRelease` still compiles on CI
                // before secrets are added; produces a debug-signed
                // release-optimised APK. Useful for early in-house
                // testing, but the Updater can't drop these onto an
                // existing install without an uninstall.
                signingConfigs.getByName("debug")
            }
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

// v0.1.41: strip Kotlin's auto-generated null-check intrinsics on
// every public-API call site in release builds. We control both ends
// of every type that crosses these boundaries; the intrinsics are
// belt-and-braces against external callers passing null, which never
// happens in a single-APK shipped app. Removing them shrinks the
// release bytecode (~3–5 % across the dex graph) and speeds up hot
// dispatch paths — noticeable on the slow legacy boxes, invisible on
// modern. Debug builds keep the assertions for tooling friendliness.
// v0.1.42: switched from the deprecated `kotlinOptions { freeCompilerArgs += ... }`
// DSL to the new `compilerOptions.freeCompilerArgs.addAll(...)` API. The newer
// Kotlin Gradle Plugin (2.0+) promoted the old call to a hard compile error
// rather than just a deprecation warning, which broke the v0.1.39–v0.1.41
// release builds in CI.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("Release", ignoreCase = true)) {
        compilerOptions.freeCompilerArgs.addAll(
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
            "-Xno-param-assertions",
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

    // Coil — async image loading for tm:rw brand logos in the tablet
    // brand picker (v0.1.72). Uses the app's OkHttp under the hood; 2.6.0
    // matches Compose BOM 2024.04 + Kotlin 2.0 and supports minSdk 21
    // (so the legacy flavor's minSdk 23 is covered).
    implementation("io.coil-kt:coil-compose:2.6.0")

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
