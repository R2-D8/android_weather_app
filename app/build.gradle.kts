plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.r2d8.weather"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.r2d8.weather"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // The release key lives outside the project (~/.android-keys) and its settings in the
    // user-level ~/.gradle/gradle.properties, so neither is ever committed. Without them the
    // release APK comes out unsigned (app-release-unsigned.apk) and cannot be installed.
    val releaseStore = providers.gradleProperty("weatherReleaseStoreFile").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("weatherReleaseStorePassword").get()
                keyAlias = providers.gradleProperty("weatherReleaseKeyAlias").get()
                keyPassword = providers.gradleProperty("weatherReleaseKeyPassword").get()
            }
        } else {
            logger.warn("weatherReleaseStoreFile is not set: the release APK will be unsigned.")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded builds to share with friends: signed with the app's own release key.
            // Updates install over an existing copy only if signed with the same key.
            signingConfig = signingConfigs.findByName("release")
            // Only 64-bit ARM, which practically every Android 13 phone uses; this drops the map
            // engine's native code for three other CPU types and makes the APK much smaller.
            // Debug builds keep all of them, so an emulator still works.
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room writes its database schema as JSON here; commit these files, they document migrations.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.maplibre.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
