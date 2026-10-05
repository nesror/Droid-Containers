plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "cn.yzapp.androidcontainer.feature.compose"
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
    implementation(project(":core:engine"))
    // 模板包内购：只依赖 EntitlementRepository 接口
    implementation(project(":core:billing"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // SAF 导入 compose YAML（rememberLauncherForActivityResult）
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
