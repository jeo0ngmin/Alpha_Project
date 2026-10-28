import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Firebase
    id("com.google.gms.google-services")
}

// API 키는 프로젝트 루트의 secrets.properties에서 읽는다 (README 3-3, 커밋 금지)
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.medimate"
    compileSdk {
        version = release(37)
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.medimate"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        listOf("CLOVA_OCR_INVOKE_URL", "CLOVA_OCR_SECRET", "OPENAI_API_KEY", "DATA_GO_KR_SERVICE_KEY",
               "KAKAO_NATIVE_APP_KEY", "CLAUDE_API_KEY").forEach { key ->
            buildConfigField("String", key, "\"${secrets.getProperty(key, "")}\"")
        }
        // 카카오 AuthCodeHandlerActivity의 scheme(kakao{키})에 쓴다 (README 7-5)
        manifestPlaceholders["KAKAO_NATIVE_APP_KEY"] = secrets.getProperty("KAKAO_NATIVE_APP_KEY", "")
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
    packaging {
        // anthropic-java가 가져오는 httpclient5 jar들이 같은 이름의 메타 파일을 가지고 있다
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    sourceSets {
        // 단위 테스트가 규칙 표(assets/supplement_rules.json)를 사본 없이 읽는다
        getByName("test").resources.srcDir("src/main/assets")
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.material)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    implementation(libs.ucrop)
    implementation(libs.mlkit.text.recognition.korean)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)

    // Firebase (README 7장)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kakao.user)
    implementation(libs.anthropic.java)
}
