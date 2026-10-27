import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.customkey.vietime"
    compileSdk = 35
    defaultConfig {
        applicationId = "nm.txa.telex"
        minSdk = 26; targetSdk = 35
        versionCode = 8; versionName = "0.7.0-alpha"
        // Bộ bắt crash native (libtxaguard.so) cho mọi CPU Android phổ biến.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    // Khoá ký release: keystore.properties ở gốc project (gitignored). Thiếu file ⇒ release không ký.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    if (keystoreProps.isNotEmpty()) signingConfigs {
        create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes { release {
        if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        isMinifyEnabled = true; isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Blob từ điển/LM đọc bằng mmap — không nén trong APK.
    androidResources { noCompress += listOf("bin") }
    testOptions { unitTests.all { it.maxHeapSize = "2g" } }
    // Module Play Feature Delivery (on-demand) — chỉ vào AAB; APK debug/GitHub không có.
    dynamicFeatures += setOf(":translate")
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // TxTelex Plus — Google Play Billing (chỉ hoạt động khi PlusGate.PAYWALL_ENABLED = true).
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    // Tải module Dịch on-demand từ Google Play.
    implementation("com.google.android.play:feature-delivery:2.1.0")
    implementation("com.google.android.play:feature-delivery-ktx:2.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
// Bộ test chậm (benchmark/eval): VT_SLOW_TESTS=1 bật.
tasks.withType<Test>().configureEach {
    inputs.property("vtSlowTests", System.getenv("VT_SLOW_TESTS") ?: "")
}
