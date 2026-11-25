plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.glasstube"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.glasstube"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "1.3.8"
    }

    // Fixed signing key kept in the repo, so APKs built on GitHub and in Android Studio
    // carry the same signature and a new version installs over the old one.
    signingConfigs {
        create("glass") {
            storeFile = file("glass.keystore")
            storePassword = "glassbrowser"
            keyAlias = "glass"
            keyPassword = "glassbrowser"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("glass")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("glass")
        }
    }

    compileOptions {
        // NewPipe Extractor uses newer Java APIs than Android 8 ships with
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // YouTube data without an API key
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Google sign-in page
    implementation("androidx.webkit:webkit:1.12.1")

    // Player and background playback
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")

    // Thumbnails
    implementation("io.coil-kt:coil:2.7.0")
}
