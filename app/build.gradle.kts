import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.hushmessenger.xposed"
    // Android 17 is API 37. compileSdkMinor = 0 selects the "android-37.0"
    // platform package (AGP names minor platforms this way).
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.hushmessenger.xposed"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Personal-use release: sign with the debug key so the GitHub Actions
            // artifact is installable without extra secrets.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Keep the modern Xposed metadata in the APK and nothing else from
            // the Java resource tree. Service descriptors stay because
            // kotlinx-coroutines registers its main-dispatcher and exception
            // handler factories through META-INF/services.
            merges += "META-INF/xposed/*"
            merges += "META-INF/services/**"
            excludes += "**"
        }
        jniLibs {
            // Required by DexKit under LSPosed: keeps libdexkit.so extractable
            // so System.loadLibrary("dexkit") works.
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The framework provides the API at runtime.
    compileOnly(libs.libxposed.api)

    // Service side: lets the settings app talk to the Xposed framework and use
    // remote preferences. It also merges the XposedProvider into our manifest.
    implementation(libs.libxposed.service)

    // Runtime DEX matching.
    implementation(libs.dexkit)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
