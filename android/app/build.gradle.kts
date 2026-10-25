plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.scan3d"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.scan3d"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.material)

    // Camera
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // AI trên thiết bị: mô hình nhận diện vật thể có sẵn của Google ML Kit (không cần huấn luyện)
    implementation(libs.mlkit.objectdetection)

    // Gửi ảnh và tải mô hình 3D từ server
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
