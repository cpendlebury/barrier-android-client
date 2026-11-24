plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release keystore for local release signing; F-Droid build servers have no such file,
// so the release build is left unsigned there (F-Droid signs the built APK itself).
val releaseKeystoreFile = File("/home/chris/Android/keystore/barrier-release.jks")

android {
    namespace = "com.crrpendlebury.barrierandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.crrpendlebury.barrierandroid"
        minSdk = 22
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = File("/home/chris/Android/keystore/barrier-release.jks")
            storePassword = "barrier-release-store"
            keyAlias = "barrier"
            keyPassword = "barrier-release-store"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // F-Droid's build servers have no release keystore on disk and sign the
            // built APK themselves, so the release build must come out unsigned there.
            // Where the release keystore exists (local), keep the release build signed.
            signingConfig = if (releaseKeystoreFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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
    }
}

dependencies {
    // Compose is on the classpath and unused. The UI is res/layout/activity_main.xml.
    // Do not add a Compose screen beside that layout without removing one of them.
    // Retrofit and Gson were removed: nothing called them, and the protocol is
    // a length-prefixed TCP session, not HTTP.

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}