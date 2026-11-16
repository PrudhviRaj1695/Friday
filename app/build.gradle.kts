plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.friday.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.friday.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.8") {
        exclude(group = "com.github.k2-fsa", module = "sherpa-onnx-jvm")
    }
    implementation("org.apache.commons:commons-compress:1.27.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
