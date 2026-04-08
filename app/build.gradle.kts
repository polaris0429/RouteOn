plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.routeon"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
        buildFeatures {
            viewBinding = true
        }
    }

    defaultConfig {
        applicationId = "com.example.routeon"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        packaging {
            resources {
                pickFirsts += "**/*.kotlin_builtins"
                pickFirsts += "META-INF/*.kotlin_module"
                pickFirsts += "META-INF/native-image/okhttp/okhttp/native-image.properties"
                pickFirsts += "META-INF/native-image/**/*.properties"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.kakaomobility.knsdk:knsdk_ui:1.12.7")
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}