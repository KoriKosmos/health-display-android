plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Discord Social SDK is downloaded from the developer portal into
// third_party/discord_social_sdk/ (gitignored). Without it the app still builds,
// with presence disabled.
val discordAar: File? = rootProject.file("third_party/discord_social_sdk")
    .walkTopDown().firstOrNull { it.name == "discord_partner_sdk.aar" }
val hasDiscordSdk = discordAar != null

android {
    namespace = "dev.korikosmos.healthdisplay"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.korikosmos.healthdisplay"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.0"
        buildConfigField("boolean", "HAS_DISCORD_SDK", hasDiscordSdk.toString())
        ndk { abiFilters += "arm64-v8a" }
    }

    ndkVersion = "28.2.13676358"
    if (hasDiscordSdk) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true // android.util.Log in AppLog
    }
    buildFeatures {
        compose = true
        buildConfig = true
        prefab = hasDiscordSdk
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.health.connect)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    if (discordAar != null) {
        implementation(files(discordAar))
        implementation(libs.androidx.browser) // required by the SDK's Android glue
    }

    testImplementation(libs.junit)
    testImplementation(libs.org.json) // real org.json for parsing tests (Android's is a stub on the JVM)
}
