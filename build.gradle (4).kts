plugins {
    alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose); alias(libs.plugins.ksp); alias(libs.plugins.hilt)
}
android {
    namespace = "app.iptv.feature.search"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core:domain")); implementation(project(":core:data")); implementation(project(":core:ui"))
    implementation(platform(libs.compose.bom)); implementation(libs.compose.ui); implementation(libs.compose.material3)
    implementation(libs.tv.material); implementation(libs.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime); implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler); implementation(libs.hilt.navigation.compose)
    implementation(libs.coil.compose); implementation(libs.paging.compose)
}
dependencies { implementation(libs.androidx.activity.compose) }
