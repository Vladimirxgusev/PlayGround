import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.pomoshnik"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pomoshnik"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // CI signs every build with the same cached key (see .github/workflows/android.yml),
    // so new APKs install over old ones and the Google sign-in fingerprint stays the same.
    val stableKeystore = System.getenv("SEKRETUTKA_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (stableKeystore != null) {
            create("stable") {
                storeFile = stableKeystore
                storePassword = "sekretutka"
                keyAlias = "sekretutka"
                keyPassword = "sekretutka"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (stableKeystore != null) signingConfig = signingConfigs.getByName("stable")
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
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
