import java.util.Properties

plugins {
  id("com.android.application")
  kotlin("android")
}

android {
  namespace = "com.qnvr"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.pipiqiang.qnvr"
    minSdk = 24
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  val props = Properties()
  val lp = rootProject.file("local.properties")
  if (lp.exists()) props.load(lp.inputStream())

  signingConfigs {
    create("release") {
      val storeFilePath = props.getProperty("qnvrStoreFile") ?: providers.gradleProperty("qnvrStoreFile").orNull
      if (storeFilePath != null) storeFile = file(storeFilePath)
      storePassword = props.getProperty("qnvrStorePassword")
        ?: providers.gradleProperty("qnvrStorePassword").orNull
        ?: System.getenv("QNVR_STORE_PASSWORD")
      keyAlias = props.getProperty("qnvrKeyAlias") ?: providers.gradleProperty("qnvrKeyAlias").orNull
      keyPassword = props.getProperty("qnvrKeyPassword")
        ?: providers.gradleProperty("qnvrKeyPassword").orNull
        ?: System.getenv("QNVR_KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }

  packaging {
    resources {
      excludes += 
        setOf("META-INF/DEPENDENCIES", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/LICENSE", "META-INF/LICENSE.txt")
    }
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")

  implementation("androidx.lifecycle:lifecycle-service:2.8.6")

  implementation("org.nanohttpd:nanohttpd:2.3.1")

  implementation("androidx.camera:camera-camera2:1.3.4")
  implementation("androidx.camera:camera-lifecycle:1.3.4")

  implementation(kotlin("stdlib"))
}
