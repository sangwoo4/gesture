plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// QNN SDK 경로
val qnnSdkPath = "/Users/hansung/Desktop/25-1/web_capstone/qairt/2.32.6.250402"

// QNN .so & Skel 복사 작업
tasks.register<Copy>("copyQnnLibs") {
    // .so 파일 복사 (Skel 포함)
    from(fileTree("$qnnSdkPath/lib/aarch64-android") {
        include(
            "libQnnHtp.so",
            "libQnnHtpPrepare.so",
            "libQnnSystem.so",
            "libQnnSaver.so",
            "libQnnHtpV79Stub.so"
        )
    }) {
        into("build/jniLibs/arm64-v8a")
    }

    // Skel 파일은 DSP가 읽을 수 있는 cdsp 디렉토리로
    from("$qnnSdkPath/lib/hexagon-v79/unsigned/libQnnHtpV79Skel.so") {
        into("build/cdsp")
    }

    into(layout.buildDirectory)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false
}

// 빌드 전에 copy 작업 실행되도록 연결
tasks.named("preBuild") {
    dependsOn("copyQnnLibs")
}

android {
    namespace = "com.square.aircommand"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.square.aircommand"
        minSdk = 25
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String", "HAND_DETECTOR_MODEL",
            "\"${project.findProperty("handDetectorModelName") ?: "mediapipe_hand-handdetector.tflite"}\""
        )
        buildConfigField(
            "String", "HAND_LANDMARK_MODEL",
            "\"${project.findProperty("handLandmarkModelName") ?: "mediapipe_hand-handlandmarkdetector.tflite"}\""
        )
        buildConfigField(
            "String", "GESTURE_CLASSIFIER_MODEL",
            "\"${project.findProperty("gestureClassifierModelName") ?: "update_gesture_model_cnn_2.tflite"}\""
        )

        buildConfigField(
            "String",
            "HAND_DETECTOR_MODEL",
            "\"${project.findProperty("handDetectorModelName") ?: "mediapipe_hand-handdetector.tflite"}\""
        )
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        viewBinding = true
        dataBinding = true
        buildConfig = true // buildCongifField() 사용 위함
    }

    // ✅ jniLibs 포함 (QNN .so 파일을 APK에 넣기 위함)
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("build/jniLibs")
        }
    }

    // ✅ META-INF 충돌 방지 설정
    packaging {
        resources {
            pickFirsts += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.room.external.antlr)
    implementation(libs.identity.doctypes.jvm)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.appcompat)

    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.androidx.ui.tooling)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // CameraX
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // TensorFlow Lite & QNN
    implementation(libs.tensorflow.lite)
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")

    implementation(libs.litert)
    implementation(libs.litert.api)
    implementation(libs.litert.gpu)
    implementation(libs.qnn.litert.delegate)
    implementation(libs.qnn.runtime)

    // OpenCV
    implementation(libs.opencv)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.google.android.material:material:1.11.0")
}