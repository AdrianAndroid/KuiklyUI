// core/file-manager/build.gradle.kts
// KMP 公共模块，框架无关（无 Electron / IPC / UI 依赖）。
// 测试：jvmTest（JVM）+ jsNodeTest（Kotlin/JS 跑在 Node）。

plugins {
    kotlin("multiplatform")
}

group = "com.tencent.kuikly"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }
    js(IR) {
        nodejs()
    }
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

// 逐条打印每个用例的 PASS/FAIL，便于肉眼观察（JVM 测试任务）
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
