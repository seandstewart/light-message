plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lightos.imessage"
    compileSdk = 36
    // Match NDK provisioned by mise (ANDROID_NDK_VERSION in mise.toml);
    // AGP's default NDK version is not installed.
    ndkVersion = System.getenv("ANDROID_NDK_VERSION") ?: "25.2.9519653"

    defaultConfig {
        applicationId = "com.lightos.imessage"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk { abiFilters += setOf("arm64-v8a") }
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

    buildFeatures { compose = true }

    packaging {
        jniLibs {
            // The rustpush native service is packaged as librustpush_service.so
            // but is a standalone executable. Legacy (uncompressed, extracted)
            // packaging is required so NativeServiceLauncher can run it from
            // nativeLibraryDir.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.datastore:datastore:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.5.1")
    implementation("org.unifiedpush.android:connector:3.0.10")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.thelightphone:sdk-server:0.0.12")
    implementation("com.thelightphone:sdk-shared:0.0.12")
    implementation("com.thelightphone:sdk-ui:0.0.12")
    implementation("com.thelightphone.lp3keyboard:ui:0.0.16")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.startup:startup-runtime:1.1.1")

    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.0")
}
