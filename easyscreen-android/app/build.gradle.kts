plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

// 默认信令服务器地址：从环境变量 EASYSCREEN_DEFAULT_SERVER_URL 或
// 本地 local.properties 的 easyscreen.defaultServerUrl 读取，不进入仓库
fun loadDefaultServerUrl(): String {
    val env = System.getenv("EASYSCREEN_DEFAULT_SERVER_URL")
    if (!env.isNullOrBlank()) return env
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        val props = Properties()
        f.inputStream().use { props.load(it) }
        val v = props.getProperty("easyscreen.defaultServerUrl")
        if (!v.isNullOrBlank()) return v
    }
    // 占位符——首次使用必须由用户在设置里填入实际地址
    return "ws://example.com:8081/ws"
}
val defaultServerUrl: String = loadDefaultServerUrl()

// 取当前 git 短哈希注入 BuildConfig，用于"关于"页展示构建版本
fun gitCommitHash(): String {
    return try {
        val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        if (out.isNotEmpty()) out else "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
val gitCommit: String = gitCommitHash()

android {
    namespace = "to.feng.app.easyscreen"
    compileSdk = 34

    defaultConfig {
        applicationId = "to.feng.app.easyscreen"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // release 签名信息从环境变量注入（CI 用 Secrets）；本地缺失时 release 回退 debug 签名
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        compose = true
        buildConfig = true
    }
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
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WebRTC
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // OkHttp for WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.5")
}
