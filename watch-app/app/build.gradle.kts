plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "demo.echoband.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "demo.echoband.watch"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(files("libs/samsung-health-sensor-api.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
