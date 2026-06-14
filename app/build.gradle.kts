plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
}

android {
    namespace = "com.shuaji.cards"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shuaji.cards"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.3.7"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // 本仓库尚未配置正式发布密钥库。
        // release 构建复用 Android SDK 自带的 debug.keystore，
        // 目的只是让产物可被 adb / 用户正常安装（未签名 APK 会因
        // INSTALL_PARSE_FAILED_NO_CERTIFICATES 报错）。这不是生产级签名。
        create("releaseDebugSigned") {
            val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("releaseDebugSigned")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")
    // 现代化调色板：HSV 圆形色环 + 多滑动条（BrightnessSlider / AlphaSlider / SaturationSlider）
    // 1.1.2 是 Maven Central 上最新的 Kotlin 2.0 编译版本（Kotlin 2.1.20 兼容）
    implementation("com.github.skydoves:colorpicker-compose:1.1.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    kapt("androidx.room:room-compiler:2.7.2")
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.20")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.20")
    }
}

// ── ktlint ────────────────────────────────────────────────────────
ktlint {
    // 只看 src 下的 Kotlin 文件
    filter {
        include("**/kotlin/**")
        exclude("**/build/**")
    }
}
