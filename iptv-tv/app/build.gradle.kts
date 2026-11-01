plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.iptv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.iptv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // URL del servidor de sincronización precargada: gradle assembleDebug -PsyncUrl=https://iptv.midominio.com
        // Lista M3U precargada en el primer arranque (se puede cambiar con -PdefaultPlaylistUrl=...; vacío = ninguna)
        buildConfigField(
            "String", "DEFAULT_PLAYLIST_URL",
            "\"${(project.findProperty("defaultPlaylistUrl") as String?) ?: "https://gist.githubusercontent.com/jvlopezlillo/2390468a78233293c9ce2294b72fcd2d/raw/lista.m3u"}\"",
        )
        buildConfigField("String", "DEFAULT_SYNC_URL", "\"${(project.findProperty("syncUrl") as String?) ?: ""}\"")
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Enviar a Chromecast (solo móvil/tablet con Google Play Services)
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
