plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cn.yzapp.androidcontainer.core.billing"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    // 渠道维度（与 app 模块同名维度配对）：global=Play 分发（内购），cn=国内分发（免购）
    flavorDimensions += "channel"
    productFlavors {
        create("global") {
            dimension = "channel"
        }
        create("cn") {
            dimension = "channel"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Google Play 依赖被隔离在 core:billing 的 global 源集内：feature 层只依赖 EntitlementRepository 接口；
    // cn 源集（国内分发）不引入 Play Billing，免购实现见 src/cn/kotlin
    "globalApi"(libs.play.billing.ktx)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
