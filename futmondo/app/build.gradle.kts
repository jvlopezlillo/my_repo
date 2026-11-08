plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.futmondo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.futmondo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.work:work-runtime:2.9.1")
}
