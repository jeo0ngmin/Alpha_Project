plugins {
    alias(libs.plugins.android.application)
}

// 백엔드 주소. 에뮬레이터는 10.0.2.2가 PC의 localhost다.
// 실제 폰으로 시험할 때는 gradle.properties(또는 ~/.gradle/gradle.properties)에
//   medimate.apiBaseUrl=http://<PC의 IP>:8080/
// 을 넣는다. 끝의 /는 필수.
val apiBaseUrl = providers.gradleProperty("medimate.apiBaseUrl").orElse("http://10.0.2.2:8080/").get()

android {
    namespace = "com.medimate"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.medimate"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.recyclerview)
    implementation(libs.exifinterface)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.glide)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
