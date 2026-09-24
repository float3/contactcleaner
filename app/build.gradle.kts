plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.contactcleaner"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.contactcleaner"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
