plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.futmondo.app"
    compileSdk = 35

    // Firma estable: la clave NO está en el repositorio; GitHub Actions la restaura desde secretos y la pasa por
    // variables de entorno. Sin ellas se usa la clave de depuración local (los APK no serán actualizables entre sí).
    val keystorePath = System.getenv("FUTMONDO_KEYSTORE")
    if (keystorePath != null) {
        signingConfigs {
            create("shared") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("FUTMONDO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FUTMONDO_KEY_ALIAS")
                keyPassword = System.getenv("FUTMONDO_KEY_PASSWORD")
            }
        }
    }

    // GitHub Actions numera cada ejecución; así cada APK tiene un versionCode mayor que el anterior.
    val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

    defaultConfig {
        applicationId = "com.futmondo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = build
        versionName = "0.1.$build"
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
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
