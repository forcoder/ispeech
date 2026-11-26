// 应用模块：智语 iSpeech 输入法
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 发布签名（凭据不入库，VOCA-477）：按优先级读取——
// 1) 环境变量 ISPEECH_STORE_FILE / ISPEECH_STORE_PASSWORD / ISPEECH_KEY_ALIAS / ISPEECH_KEY_PASSWORD（CI、远端构建机）；
// 2) 仓库内 keystore/keystore.properties（.gitignore 忽略，从 keystore/keystore.properties.example 复制）；
// 3) 机器级 ~/.gradle/ispeech-signing.properties（git pull 不删除，适合长期构建机）。
// 全部缺失时 release 产出未签名 APK 并打印告警，未持有证书的协作者不受影响。
val envStoreFile = System.getenv("ISPEECH_STORE_FILE")
val envStorePassword = System.getenv("ISPEECH_STORE_PASSWORD")
val envKeyAlias = System.getenv("ISPEECH_KEY_ALIAS")
val envKeyPassword = System.getenv("ISPEECH_KEY_PASSWORD")
val hasEnvSigning = listOf(envStoreFile, envStorePassword, envKeyAlias, envKeyPassword).all { !it.isNullOrBlank() }

fun loadProps(file: File): Properties? =
    if (file.exists()) Properties().apply { file.inputStream().use { load(it) } } else null

val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val userKeystorePropsFile = File(System.getProperty("user.home"), ".gradle/ispeech-signing.properties")
val keystoreProps = loadProps(keystorePropsFile) ?: loadProps(userKeystorePropsFile)
val hasFileSigning = keystoreProps?.let {
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { k -> it.getProperty(k)?.isNotBlank() == true }
} == true

// 离线语音模型下载地址（配置项）：默认走阿里云 OSS 公共读对象（HTTPS + Range，
// 默认域名免 ICP 备案，baseline 见 docs/model-host-runbook.md）。
// 打包时可用 -PasrModelUrl=https://... 覆盖（例如切到备用源），无需改业务代码。
val asrModelUrl: String = (project.findProperty("asrModelUrl") as String?)
    ?: "https://delivery-service-smzk.oss-cn-guangzhou.aliyuncs.com/models/asr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2"

// Vosk 中文小模型地址（配置项，`VoskModelStore` 使用）：默认仍是 Vosk 官方源
// （HTTPS + Range；实测 application/zip / 43898754 字节，见 docs/model-host-runbook.md §10）。
// 打包时可用 -PvoskModelUrl=https://... 覆盖；镜像到自有 OSS 属外部资源变更，需另行拍板。
val voskModelUrl: String = (project.findProperty("voskModelUrl") as String?)
    ?: "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

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

        // 离线语音模型下载地址（见文件顶部 asrModelUrl 配置项）
        buildConfigField("String", "ASR_MODEL_URL", "\"$asrModelUrl\"")

        // Vosk 中文小模型下载地址（见文件顶部 voskModelUrl 配置项）
        buildConfigField("String", "VOSK_MODEL_URL", "\"$voskModelUrl\"")
    }

    signingConfigs {
        if (hasEnvSigning) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(envStoreFile))
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        } else if (hasFileSigning) {
            val props = keystoreProps!!
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasEnvSigning || hasFileSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("release 未找到签名凭据：将产出未签名 APK。按 keystore/README.md 配置环境变量或本地属性文件。")
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

    // FunASR 离线识别（基于 sherpa-onnx 的第三方封装）
    implementation(libs.sherpa.onnx)
    // 解压 .tar.bz2 模型包（Android 无 tar 可执行文件，JDK 不支持 bzip2）
    implementation(libs.commons.compress)

    // 端侧语音识别（离线 Vosk；模型不入包，首次使用时下载到 filesDir）
    implementation(libs.vosk.android)

    // 单元测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation(kotlin("test"))
}
