plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "com.example.fd.video.recorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.fd.video.multimicrecorder"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // デフォルトの設定値
        // 動画の最大ファイルサイズ
        val fileSize = 1*1000*1000*1000 // 1GB
        // 録画に使うマイクのタイプ
        val micType = "xfe" // or "5ch" or "xfe" or "raw" or "normal"
        // プレビューの有効化有無
        val enablePreview = true
        // Visionの有効化有無
        val enableVision = true
        // Visionのサーバーポート
        val visionPort = 8080

        buildConfigField("long", "FILE_SIZE", "$fileSize")
        buildConfigField("String", "MIC_TYPE", "\"$micType\"")
        buildConfigField("boolean", "ENABLE_PREVIEW", "$enablePreview")
        buildConfigField("boolean", "ENABLE_VISION", "$enableVision")
        buildConfigField("int", "VISION_PORT", "$visionPort")
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.accompanist.permissions)

    implementation(libs.kotlinx.coroutine.guava)

    // AndroidX標準のCameraXを追加
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // 代わりに，THINKLETカスタムのCamera－Videoを追加．
    implementation(thinkletLibs.camerax.video)

    // THINKLET向けのマイクを追加
    implementation(thinkletLibs.camerax.mic.core)
    implementation(thinkletLibs.camerax.mic.multi.channel)
    implementation(thinkletLibs.camerax.mic.xfe)
    // THINKLET向けのSDKを追加
    implementation(thinkletLibs.sdk.audio)

    // https://github.com/FairyDevicesRD/thinklet.camerax.vision
    implementation(project(":vision"))

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
