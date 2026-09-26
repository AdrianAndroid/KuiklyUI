pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/gradle-plugins/")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/")
        }
    }
}

val buildFileName = "build.2.0.ohos.gradle.kts"
rootProject.buildFileName = buildFileName


include(":core-annotations")
project(":core-annotations").buildFileName = buildFileName

include(":core-ksp")
project(":core-ksp").buildFileName = buildFileName

include(":core")
project(":core").buildFileName = buildFileName

include(":core-render-android")
project(":core-render-android").buildFileName = buildFileName

include(":compose")
project(":compose").buildFileName = buildFileName

include(":core-wx")
project(":core-wx").buildFileName = buildFileName

include(":demo")
project(":demo").buildFileName = buildFileName

// 双栏文件管理器核心（纯状态机）：OHOS 变体单独 build 文件，额外加 ohosArm64
include(":core:file-manager")
project(":core:file-manager").buildFileName = "build.ohos.gradle.kts"

// include(":androidApp")

