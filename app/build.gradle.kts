plugins {
    id("com.android.application")
}

android {
    namespace = "phoenix.smsnotify"
    compileSdk = 36

    defaultConfig {
        applicationId = "phoenix.smsnotify"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
