plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace="com.blufox.vpn"
 compileSdk=35
 defaultConfig {
  applicationId="com.blufox.vpn"
  minSdk=26
  targetSdk=35
  versionCode=3
  versionName="0.3.0"
  ndk { abiFilters += setOf("arm64-v8a") }
 }
 buildTypes { release { isMinifyEnabled=false;proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro") } }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.09.03"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("com.github.singbox-android:libbox:1.14.4")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("org.bouncycastle:bcprov-jdk18on:1.80")
 debugImplementation("androidx.compose.ui:ui-tooling")
}
