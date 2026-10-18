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
        versionCode = 2; versionName = "0.2.0-alpha"
    }
    buildTypes { release {
        isMinifyEnabled = true; isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Blob từ điển/LM đọc bằng mmap — không nén trong APK.
    androidResources { noCompress += listOf("bin") }
    testOptions { unitTests.all { it.maxHeapSize = "2g" } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
// Bộ test chậm (benchmark/eval): VT_SLOW_TESTS=1 bật.
tasks.withType<Test>().configureEach {
    inputs.property("vtSlowTests", System.getenv("VT_SLOW_TESTS") ?: "")
}
