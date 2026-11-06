import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cihmine.carvoicegame"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cihmine.carvoicegame"
        // Pleos Connect 에뮬레이터 이미지가 API 34
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 단어 검색 중계 서버(server/). 비워 두면 인터넷 검색 없이 앱 안 사전만 쓴다.
        val dictProxy = providers.gradleProperty("carvoicegame.dictProxy").getOrElse("")
        buildConfigField("String", "DICT_PROXY_URL", "\"$dictProxy\"")
        // 차량(arm64)과 에뮬레이터(x86_64)만. SDK 가 끌고 오는 mips 등의 네이티브 파일을 뺀다.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        // 서명 키는 저장소 밖에 둔다. 위치는 -Pcarvoicegame.signing=경로 로 바꿀 수 있고,
        // 파일이 없으면 release 는 서명 없이 만들어진다(설치용 APK 아님).
        val props = file(
            providers.gradleProperty("carvoicegame.signing")
                .getOrElse("${System.getProperty("user.home")}/.android-keys/carvoicegame.properties"),
        )
        if (props.exists()) {
            val p = Properties().apply { props.inputStream().use(::load) }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    // Pleos Connect 음성 SDK. 차량(Pleos 음성 서비스가 있는 기기)에서만 쓰고, 없으면 안드로이드 기본 음성으로 동작한다.
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
