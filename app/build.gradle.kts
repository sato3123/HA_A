plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "jp.home.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "jp.home.panel"
        // Fire HD 8 第10世代 = Fire OS 7 (Android 9 / API 28)、第12世代 = Fire OS 8 (Android 11)
        minSdk = 28
        targetSdk = 34
        // GitHub Actions の実行番号がそのままバージョン番号になる（ローカルビルドは 1）
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        // アプリ内アップデートの確認先（GitHub の owner/repo）
        buildConfigField("String", "UPDATE_REPO", "\"sato3123/HA_A\"")
    }

    signingConfigs {
        // 更新を上書きインストールするには、毎回同じ鍵で署名する必要がある。
        // 鍵は GitHub の Secrets（KEYSTORE_BASE64 / KEYSTORE_PASSWORD）から CI が用意する。
        val ksFile = System.getenv("KEYSTORE_FILE")
        val ksPass = System.getenv("KEYSTORE_PASSWORD")
        if (!ksFile.isNullOrBlank() && !ksPass.isNullOrBlank() && file(ksFile).exists()) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = ksPass
                keyAlias = "homepanel"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // カメラの高画質な動画（Home Assistant の HLS 配信）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    // 直接つなぐネットワークカメラ（RTSP）
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    // Home Assistant のカメラをリアルタイム（WebRTC）で見る
    implementation("io.getstream:stream-webrtc-android:1.1.3")
    // スクリーンセーバーの写真表示
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
}
