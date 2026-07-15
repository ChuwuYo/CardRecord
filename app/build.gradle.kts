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
        versionCode = 23
        versionName = "1.5.6"

        vectorDrawables { useSupportLibrary = true }
    }

    // 单测里 Robolectric 要读 Android 资源 / Manifest，得打开 includeAndroidResources
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Robolectric 的 plugins-maven-dependency-resolver 不读 Gradle 代理配置，
                // 走默认 Sonatype 直连会卡超时。本地开发环境：
                //   提前用 `curl --proxy 127.0.0.1:18080` 把
                //   android-all-instrumented-13-robolectric-9030017-i6.jar（**注意是 i6 不是 i7**）下到
                //   `~/.m2/repository/org/robolectric/android-all-instrumented/13-robolectric-9030017-i6/`
                //   沙箱里 Robolectric 会先扫本地仓库命中，不会去网络下
                //   本地能直连 Maven Central 时可删掉这一段
                val mirror = System.getenv("ROBOLECTRIC_MIRROR")
                if (mirror != null) {
                    it.systemProperty("robolectric.dependency.repo.url", mirror)
                }
                if (System.getenv("ROBOLECTRIC_OFFLINE") == "true") {
                    it.systemProperty("robolectric.offline", "true")
                    System.getenv("ROBOLECTRIC_SDK_DIR")?.let { sdkDir ->
                        it.systemProperty("robolectric.dependency.dir", sdkDir)
                    }
                }
            }
        }
    }

    signingConfigs {
        create("release") {
            // 正式密钥只由本机环境变量或 GitHub Actions Secrets 注入，不进入仓库。
            providers.environmentVariable("CARDRECORD_RELEASE_STORE_FILE").orNull?.let {
                storeFile = file(it)
            }
            storePassword = providers.environmentVariable("CARDRECORD_RELEASE_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("CARDRECORD_RELEASE_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("CARDRECORD_RELEASE_KEY_PASSWORD").orNull
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    // 应用内语言切换（per-app language 官方方案）：AppCompatDelegate.setApplicationLocales
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
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
    // 版本与当前 Kotlin / Compose 工具链兼容。
    implementation("com.github.skydoves:colorpicker-compose:1.1.2")
    // 种子色 → Material 3 配色：MaterialKolor 封装 Google material-color-utilities 的
    // HCT / tonal palette 官方算法（Material You）。2.1.1 用 Kotlin 2.1.20 + Compose 1.7.3 编译，
    // 与本项目工具链一致，避免自己写 HSL 近似。
    implementation("com.materialkolor:material-kolor:2.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    kapt("androidx.room:room-compiler:2.7.2")

    // ── 单测 ────────────────────────────────────────────────────────
    // JUnit 4 + Robolectric（Android Context / ContentResolver / Resources）
    // + Room in-memory + kotlinx-coroutines-test（runTest / TestScope）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
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
