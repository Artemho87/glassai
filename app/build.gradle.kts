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
    versionCode = 8
    versionName = "3.1"

    // Только 64-битные ARM-телефоны (все современные Android): APK заметно меньше
    ndk { abiFilters += "arm64-v8a" }

    // Developer Mode в приложении Meta AI: оба значения "0"
    manifestPlaceholders["mwdat_application_id"] = "0"
    manifestPlaceholders["mwdat_client_token"] = "0"
  }

  // Собственный ключ подписи (не отладочный): банковские антивирусы ругаются на отладочные сборки
  signingConfigs {
    create("release") {
      storeFile = file("release.keystore")
      storePassword = "glassai2026"
      keyAlias = "glassai"
      keyPassword = "glassai2026"
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      isDebuggable = false
      signingConfig = signingConfigs.getByName("release")
    }
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
