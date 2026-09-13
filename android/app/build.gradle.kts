plugins { id("com.android.application") }
android {
    namespace = "com.aliothmoon.maameow"
    compileSdk = 36
    ndkVersion = "29.0.13113456"
    signingConfigs.getByName("debug") {
        providers.environmentVariable("MOWER_DEBUG_KEYSTORE_PATH").orNull?.let {
            storeFile = file(it)
        }
    }
    defaultConfig {
        applicationId = "io.github.alexsun0.mower.android"
        minSdk = 27
        targetSdk = 28
        versionCode = 26
        versionName = "0.2.6"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("debug"); isMinifyEnabled = false } }
    buildFeatures { aidl = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    externalNativeBuild { cmake { path = file("src/main/native/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs { useLegacyPackaging = true } }
    androidResources { noCompress += listOf("zip", "xz") }
    // PRoot currently needs targetSdk 28. This APK is distributed through GitHub,
    // not Google Play; retain all other release lint checks.
    lint { disable += "ExpiredTargetSdkVersion" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("org.tukaani:xz:1.10")
    implementation("androidx.core:core:1.17.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    compileOnly(project(":hidden-api"))
}
