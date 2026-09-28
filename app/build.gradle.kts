plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun prop(name: String): String = providers.gradleProperty(name).get()

android {
    namespace = "com.titanvps.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.titanvps.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUB_HOSTS", "\"${prop("titan.subHosts")}\"")
        buildConfigField("String", "TELEGRAM_URL", "\"${prop("titan.telegramUrl")}\"")
        buildConfigField("String", "WEBSITE_URL", "\"${prop("titan.websiteUrl")}\"")
        buildConfigField("String", "LOGIN_URL", "\"${prop("titan.loginUrl")}\"")
        manifestPlaceholders["appLinkHost"] = prop("titan.appLinkHost")

        ndk {
            // libXray.aar ships these ABIs; keep only what you distribute.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Built by scripts/build-libxray.sh
    implementation(files("libs/libXray.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation(libs.json)
}
