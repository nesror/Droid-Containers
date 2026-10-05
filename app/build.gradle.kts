import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// 签名配置从 keystore.properties（工程根目录，不入库）读取
val keystoreProperties = Properties().apply {
  val f = rootProject.file("keystore.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}

val keystoreConfigValid: Boolean by lazy {
  val storeFile = keystoreProperties.getProperty("storeFile")
  val storePassword = keystoreProperties.getProperty("storePassword")
  val keyAlias = keystoreProperties.getProperty("keyAlias")
  val keyPassword = keystoreProperties.getProperty("keyPassword")
  !storeFile.isNullOrBlank() && !storePassword.isNullOrBlank() &&
    !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank() &&
    !storePassword.startsWith("CHANGE_ME") && !keyAlias.startsWith("CHANGE_ME") &&
    !keyPassword.startsWith("CHANGE_ME") && rootProject.file(storeFile).exists()
}

// Google Play 上传密钥配置（与 release 密钥相互独立）
val playUploadConfigValid: Boolean by lazy {
  val storeFile = keystoreProperties.getProperty("playUploadStoreFile")
  val storePassword = keystoreProperties.getProperty("playUploadStorePassword")
  val keyAlias = keystoreProperties.getProperty("playUploadKeyAlias")
  val keyPassword = keystoreProperties.getProperty("playUploadKeyPassword")
  !storeFile.isNullOrBlank() && !storePassword.isNullOrBlank() &&
    !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank() &&
    rootProject.file(storeFile).exists()
}

android {
    namespace = "cn.yzapp.androidcontainer"
    compileSdk = 37
    defaultConfig {
        applicationId = "cn.yzapp.androidcontainer"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.2.3"
    }

    // 渠道维度：global=Play 分发（内购、收费模板照常）；cn=国内分发（免购、隐藏收费模板、Web 服务可直接开启）
    flavorDimensions += "channel"
    productFlavors {
        create("global") {
            dimension = "channel"
        }
        create("cn") {
            dimension = "channel"
            // 独立包名后缀，可与 Play 版并存安装测试；如需单一包名分发删除此行即可
            applicationIdSuffix = ".cn"
            versionNameSuffix = "-cn"
        }
    }

    signingConfigs {
        create("release") {
            // storeFile 相对于工程根目录（android-container-master）解析
            storeFile = keystoreProperties["storeFile"]?.let { rootProject.file(it) }
            storePassword = keystoreProperties["storePassword"] as String?
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
        }
        // Google Play 上传密钥：Play App Signing 下上传包必须用不同于应用签名密钥的上传密钥签名
        create("playUpload") {
            storeFile = keystoreProperties["playUploadStoreFile"]?.let { rootProject.file(it) }
            storePassword = keystoreProperties["playUploadStorePassword"] as String?
            keyAlias = keystoreProperties["playUploadKeyAlias"] as String?
            keyPassword = keystoreProperties["playUploadKeyPassword"] as String?
        }
    }

    buildTypes {
        debug {
            // debug 包也使用正式签名，保证与 release 签名一致
            if (keystoreConfigValid) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // Play 政策要求 DEX 代码优化（混淆等）达标；zstd JNI / 终端 @JavascriptInterface
            // 等 keep 规则见 proguard-rules.pro，其余库（Room/OkHttp/Billing/kotlinx-serialization）
            // 均自带 consumer 规则
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        // Google Play 渠道：用上传密钥签名（assemblePlay / bundlePlay）
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            if (playUploadConfigValid) {
                signingConfig = signingConfigs.getByName("playUpload")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      jniLibs {
        // proot 需要 exec，必须以 extractNativeLibs=true 解压到 nativeLibraryDir：
        // 未压缩打包时 Android 10+ 不会解压 native 库，运行时将报 proot binary missing
        useLegacyPackaging = true
        // 阻止 AGP strip 破坏 proot 可执行性
        keepDebugSymbols.add("**/libproot.so")
        keepDebugSymbols.add("**/libproot_loader.so")
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // 模块
  implementation(project(":core:data"))
  implementation(project(":core:billing"))
  implementation(project(":core:server"))
  implementation(project(":core:designsystem"))
  implementation(project(":feature:dashboard"))
  implementation(project(":feature:images"))
  implementation(project(":feature:containers"))
  implementation(project(":feature:compose"))
  implementation(project(":feature:settings"))

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  // billing 9.1.0 -> play-services-base 18.5.0 会把 fragment 传递到 1.1.0（Play 控制台告警
  // "SDK 版本已过时"），显式声明新版本强制依赖解析上顶
  implementation(libs.androidx.fragment)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
  implementation(libs.androidx.compose.material.icons.core)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  // 侧滑返回手势进度（蒙版跟手）
  implementation(libs.androidx.navigationevent.compose)
}
