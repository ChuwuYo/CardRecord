import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    // Room 插件把 schema 目录建模为任务输入/输出，支持正确的增量与构建缓存。
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.shuaji.cards"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shuaji.cards"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "1.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
                //   提前按测试使用的 SDK 下载对应 android-all-instrumented 制品到本地 Maven 仓库；
                //   例如 SDK 34 对应 `14-robolectric-10818077-i6`（注意是 i6）。
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
        checkReleaseBuilds = true
        abortOnError = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation("androidx.core:core-ktx:1.18.0")
    // 应用内语言切换依赖 AppCompatDelegate.setApplicationLocales。
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0"))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    // HSV 取色器。
    implementation("com.github.skydoves:colorpicker-compose:1.2.0")
    // 种子色生成 Material 3 HCT 色板。
    implementation("com.materialkolor:material-kolor:2.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    ksp("androidx.room:room-compiler:2.8.5")

    // ── 单测 ────────────────────────────────────────────────────────
    // JUnit 4 + Robolectric / Compose UI（Android 资源与界面回归）
    // + Room in-memory + kotlinx-coroutines-test（runTest / TestScope）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit-ktx:1.3.0")
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
}

ktlint {
    version.set("1.5.0")
}
