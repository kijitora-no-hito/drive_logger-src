import java.util.Properties

// 署名鍵（ルートの keystore.properties と release.jks。どちらも git 管理外・要バックアップ）。無ければ release は署名なし。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.secrets)
}

android {
    namespace = "jp.kjtr.drivelogger"
    // Google Play は 2026-08-31 以降 targetSdk 36（Android 16）以上が必須。配布は GitHub だが要件はそろえておく。
    compileSdk = 36

    defaultConfig {
        // 配布用の識別子。公開後は変更できない。
        applicationId = "jp.kjtr.drivelogger"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "0.8.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            // kotlinx-serialization / Room / CameraX の R8 リスクを避けるため minify は無効のまま。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    testOptions {
        // GPX の解析テストは kxml2（XmlPullParser の実装）を使う。android.jar のスタブに当たっても落ちないように。
        unitTests.isReturnDefaultValues = true
    }
}

// MAPS_API_KEY は local.properties に置く（リポジトリには入れない）。
// 未設定でもビルドが通るよう、無ければ local.defaults.properties のプレースホルダを使う。
secrets {
    propertiesFileName = "local.properties"
    defaultPropertiesFileName = "local.defaults.properties"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)

    // 地図は Google Maps（Maps SDK for Android + Compose ラッパー）。動画には焼き込まない（画面だけ）。
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)
    // 0.8.0〜 測位の方式「Google の融合測位」（FusedLocationProviderClient。設定で選んだときだけ。Play 開発者サービスが無ければ端末の GPS）。
    implementation(libs.play.services.location)

    // ドラレコ: プレビュー・録画（Recorder）・HUD の焼き込み（OverlayEffect）。
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.effects)
    // 写真の EXIF（撮影日時・タイムゾーン・位置）の読み書き。
    implementation(libs.androidx.exifinterface)

    // 録画したファイルの再生。
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // SNS 用の書き出し（切り出し・解像度/ビットレート・切り抜き・速度などを覆うオーバーレイ）。
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    // Room の Migration の確認（v1 のスキーマに Migration の SQL を流して v2 のスキーマと突き合わせる）。
    testImplementation(libs.sqlite.jdbc)
}
