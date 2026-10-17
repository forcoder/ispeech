// 应用模块：智语 iSpeech 输入法
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 发布签名：存在 keystore/keystore.properties 时为 release 启用签名（原型自签测试证书）；
// 文件缺失时保持未签名构建，未持有证书的协作者不受影响。
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.ispeech"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ispeech.ime"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.5"

        // 后端基础地址：与 CsBabyClient.DEFAULT_BASE_URL 保持一致
        buildConfigField("String", "API_BASE_URL", "\"http://api.agentai0.com/\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // 需要 BuildConfig.API_BASE_URL
        buildConfig = true
        compose = true
    }

    // Kotlin 1.9.24 对应的 Compose 编译器版本
    // （org.jetbrains.kotlin.plugin.compose 仅适用于 Kotlin 2.0+，此处使用 composeOptions）
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.tooling)

    // 本地存储
    implementation(libs.datastore.preferences)

    // 网络（csBaby 后端）
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)
}
