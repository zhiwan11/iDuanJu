plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    // Local color-bar fixture is Debug-only; no sample media is added to a Release artifact.
    sourceSets.getByName("debug").assets.srcDir("../../STVTests/Fixtures")
    // Exact user collection supplied locally for native QA, never bundled in a release APK.
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/novel-qa-assets"))
    sourceSets.getByName("androidTest").assets.srcDir("../../STVTests/Fixtures")

    namespace = "app.iduanju"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.iduanju"
        minSdk = 26
        targetSdk = 35
        versionCode = 1622
        versionName = "1.6.22-android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    signingConfigs {
        create("distribution") {
            val path = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = "iduanju"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) "debug" else "distribution")
        }
        debug {
            // Opt-in parallel QA install; never re-sign/uninstall or overwrite the owner's existing app.
            when (providers.gradleProperty("idjIsolatedQa").orNull) {
                "true" -> applicationIdSuffix = ".legadoport"
                "mac" -> applicationIdSuffix = ".legadoportmac" // separate local signer; preserve existing QA/user apps
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true; compose = true }
    lint {
        // lifecycle 2.9.x (pulled by backdrop/Compose 1.9) lint detector crashes under AGP 8.7.3 Kotlin Analysis API
        disable += "NullSafeMutableLiveData"
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // 推荐插件: the same .idjplugin bundles the iOS app ships (STV/Resources/Plugins), copied at build time
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/idj-assets"))
}
val copyBundledPlugins by tasks.registering(Sync::class) {
    from(rootProject.file("../STV/Resources/Plugins")) { include("*.idjplugin") }
    into(layout.buildDirectory.dir("generated/idj-assets/plugins"))
}
val copySharedLicenses by tasks.registering(Sync::class) {
    from(rootProject.file("../STV/Resources/Licenses")) { include("HGCurrentPlayer-*", "Rhino-*", "legado-E-*", "FFmpeg-LGPL-2.1.txt") }
    into(layout.buildDirectory.dir("generated/idj-assets/licenses"))
}
tasks.named("preBuild") { dependsOn(copyBundledPlugins, copySharedLicenses) }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    // OkHttp (HTTP/2) for player requests: some CDNs behind Cloudflare 403 HttpURLConnection (HTTP/1.1 only)
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    // Haze (Apache-2.0): backdrop blur for sticky bars/sheets
    implementation("dev.chrisbanes.haze:haze:1.5.0")
    // AndroidLiquidGlass (Apache-2.0): liquid glass backdrop refraction & vibrancy for floating navigation
    implementation("io.github.kyant0:backdrop:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// Same self-owned Legado port fixtures used by core JVM and iOS tests.
android.sourceSets.getByName("androidTest").assets.srcDir("../../shared/legado-port")
