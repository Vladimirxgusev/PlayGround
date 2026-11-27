import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.pomoshnik.wear"
    compileSdk = 35

    sourceSets["main"].res.srcDir(rootProject.file("app/src/main/branding/res"))

    defaultConfig {
        // Wear OS Data Layer requires the same application ID and signing key on both devices.
        applicationId = "app.pomoshnik"
        minSdk = 30
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

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
            if (stableKeystore != null) signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
