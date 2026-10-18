plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.customkey.vietime.translate"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":app"))
    // Dịch trên máy (không gửi chữ lên mạng; chỉ tải gói ngôn ngữ ~30 MB lần đầu).
    implementation("com.google.mlkit:translate:17.0.3")
    // SplitCompat.install trong MlKitTranslator (cùng bản với base).
    implementation("com.google.android.play:feature-delivery:2.1.0")
}
