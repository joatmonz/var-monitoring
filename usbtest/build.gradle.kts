plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A stand-alone diagnostic app: plug a USB camera in, and it logs everything about how
// the camera streams (descriptors, negotiation, bulk or isochronous packets, frames)
// into a file that can be sent from the phone. Kept apart from the VAR app on purpose.
android {
    namespace = "com.example.varcamera.usbtest"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.varcamera.usbtest"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            // Signed with the debug key so the APK can be sideloaded onto test phones.
            signingConfig = signingConfigs.getByName("debug")
            // Native code calls back into Kotlin by method name; keep names intact.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
