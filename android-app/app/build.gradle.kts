import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }

// 正式签名材料存放在 runtime/android-signing/（随仓库维护）；缺失时仅在构建正式版时报错提醒。
val signingProps = Properties().apply {
    val file = rootProject.file("../runtime/android-signing/keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val releaseKeystore = signingProps.getProperty("storeFile")?.let { rootProject.file(it) }?.takeIf { it.isFile }

android {
    namespace = "org.familyrobot.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.robot.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        releaseKeystore?.let { store ->
            create("release") {
                storeFile = store
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.merges += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// These local assets are intentionally untracked, but a runnable APK must include them.
val verifySpeechAssets by tasks.registering {
    val names = listOf("kws/keywords.txt", "kws/tokens.txt", "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "kws/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "vad/silero_vad.onnx")
    doLast {
        val missing = names.filter { !file("src/main/assets/models/$it").isFile }
        check(missing.isEmpty()) { "缺少本地语音资源：${missing.joinToString()}；请按模型安装说明准备 assets/models 后再打包。" }
    }
}
tasks.named("preBuild") { dependsOn(verifySpeechAssets) }
