plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "id.mytrack.app"
    val buildVersionCode = providers.environmentVariable("MYTRACK_VERSION_CODE").orElse("3").get().toInt()
    val buildVersionName = providers.environmentVariable("MYTRACK_VERSION_NAME").orElse("1.0.2").get()
    val signingStoreFile = providers.environmentVariable("MYTRACK_KEYSTORE_FILE").orNull
    val signingStorePassword = providers.environmentVariable("MYTRACK_KEYSTORE_PASSWORD").orNull
    val signingKeyAlias = providers.environmentVariable("MYTRACK_KEY_ALIAS").orNull
    val signingKeyPassword = providers.environmentVariable("MYTRACK_KEY_PASSWORD").orNull
    val hasReleaseSigning = signingStoreFile != null && signingStorePassword != null &&
        signingKeyAlias != null && signingKeyPassword != null

    compileSdk = 35

    signingConfigs {
        if (hasReleaseSigning) {
            create("stableRelease") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "id.mytrack.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = buildVersionName
        buildConfigField("String", "SUPABASE_URL", "\"https://tyhulcbdqrvnpkillrij.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"sb_publishable_exImSNccvxIoi_kO3TxxcQ_itatkoa_\"")
    }

    buildTypes {
        debug {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("stableRelease")
        }
        release {
            if (!hasReleaseSigning) throw GradleException("MYtrack stable signing secrets are required for release builds.")
            signingConfig = signingConfigs.getByName("stableRelease")
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // LatLng is retained only as a coordinate data type; map rendering uses OpenStreetMap.
    implementation("com.google.android.gms:play-services-maps:19.2.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
