plugins { id("com.android.library") }
android { namespace = "com.aliothmoon.hidden_api"; compileSdk = 36; defaultConfig { minSdk = 27 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
