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

        // Default settings
        // Maximum video file size
        val fileSize = 1*1000*1000*1000 // 1GB
        // Microphone type for recording
        val micType = "raw" // or "5ch" or "xfe" or "raw" or "normal"
        // Enable/disable preview
        val enablePreview = true
        // Enable/disable Vision
        val enableVision = false
        // Vision server port
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

    // Add standard AndroidX CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Add THINKLET custom Camera-Video instead.
    implementation(thinkletLibs.camerax.video)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Add microphone for THINKLET
    implementation(thinkletLibs.camerax.mic.core)
    implementation(thinkletLibs.camerax.mic.multi.channel)
    implementation(thinkletLibs.camerax.mic.xfe)
    // Add SDK for THINKLET
    implementation(thinkletLibs.sdk.audio)
    implementation(thinkletLibs.sdk.led)
    implementation(thinkletLibs.sdk.maintenance)

    // https://github.com/FairyDevicesRD/thinklet.camerax.vision
    implementation(project(":vision"))

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
