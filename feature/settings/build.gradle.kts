plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "cn.yzapp.androidcontainer.feature.settings"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    // 渠道维度（与 app/core:billing 配对，全链路声明以保证变体匹配）
    flavorDimensions += "channel"
    productFlavors {
        create("global") { dimension = "channel" }
        create("cn") { dimension = "channel" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 首次启动引导页的权限申请（rememberLauncherForActivityResult）
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
