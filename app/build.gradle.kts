import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.artem.glassai"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.artem.glassai"
    minSdk = 31
    targetSdk = 36
    versionCode = 7
    versionName = "3.0"

    // Только 64-битные ARM-телефоны (все современные Android): APK заметно меньше
    ndk { abiFilters += "arm64-v8a" }

    // Developer Mode в приложении Meta AI: оба значения "0"
    manifestPlaceholders["mwdat_application_id"] = "0"
    manifestPlaceholders["mwdat_client_token"] = "0"
  }

  // Постоянный ключ подписи: обновления ставятся поверх, без удаления приложения
  signingConfigs {
    getByName("debug") {
      storeFile = file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      // Подпись всеми схемами: некоторые установщики (SAI, оболочки Xiaomi/Huawei) ищут старую v1
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    debug { signingConfig = signingConfigs.getByName("debug") }
    release { isMinifyEnabled = false }
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.inputs)
}
