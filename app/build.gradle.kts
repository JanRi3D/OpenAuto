plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "me.ri3d.openauto"
    compileSdk = 36
    ndkVersion = "23.2.8568313" // the last NDK that still builds for API 16

    defaultConfig {
        applicationId = "me.ri3d.openauto"
        minSdk = 16
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // src/main/cpp/systls.c: JNI shim over the device's own OpenSSL (no bundled crypto code).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Shrink (not obfuscate) the debug build too: BouncyCastle alone exceeds the single-dex
            // method limit, and minSdk 16 has no native multidex. Unused BC code is removed by R8.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-debug.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        abortOnError = true
        // NewApi and MissingPermission stay fatal: they guard the API 16 baseline and runtime permissions.
        disable += listOf(
            "SpUsage",               // text sizes are dp on purpose: head units have no font scaling and fixed layouts
            "RtlHardcoded",          // landscape head-unit UI, English, left-to-right only
            "UnusedResources",       // strings are added one stage ahead of the code that uses them
            "DuplicateIncludedIds",  // tile_main/tile_small are included twice and looked up via their parent
            "NestedWeights", "UselessParent", "RelativeOverlap",
            "DiscouragedApi",        // fixed landscape orientation is the point of a head-unit app
            "OldTargetApi", "AndroidGradlePluginVersion", "GradleDependency", "DataExtractionRules"
        )
    }
}

dependencies {
    implementation(libs.bcprov)
    implementation(libs.bctls)
    testImplementation(libs.junit)
}
