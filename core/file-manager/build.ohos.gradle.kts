// core/file-manager/build.gradle.kts
// KMP 公共模块，框架无关（无 Electron / IPC / UI 依赖）。
// 目标：jvm / js / android / ios(x64,arm64,simArm64) / macos(x64,arm64)，OHOS 变体下额外加 ohosArm64。
// 测试：jvmTest（JVM）+ jsNodeTest（Kotlin/JS 跑在 Node）。

plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

group = "com.tencent.kuikly"
version = "0.1.0"

repositories {
    google()
    mavenCentral()
}

// 仅在 OHOS 构建变体下由 build.ohos.gradle.kts 额外加入 ohosArm64
// （标准 Kotlin 认识不了 ohosArm64，不能在共享脚本里引用）。

kotlin {
    jvm {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }
    js(IR) {
        nodejs()
    }
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()
    ohosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "com.tencent.kuikly.core.file.manager"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 逐条打印每个用例的 PASS/FAIL，便于肉眼观察（JVM 测试任务）
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
