plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.vieneu.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35

        ndk {
            // arm64-v8a only — matches real devices and the Apple Silicon
            // Android Studio emulator system images (see design spec §3.1).
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // libsea_g2p_android.so (built by cargo-ndk) already lives under
    // src/main/jniLibs/arm64-v8a/ — AGP picks it up automatically.
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.onnxruntime.android)
    // JNA's Android build is published as an .aar under the same coordinate —
    // the @aar classifier is required (plain .jar won't include native loader glue).
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
