plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvm()

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    watchosDeviceArm64()
    watchosSimulatorArm64()
    watchosArm32()
    watchosArm64()

    tvosArm64()
    tvosSimulatorArm64()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX64()
    androidNativeX86()

    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64()

    js {
        browser()
        nodejs()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    explicitApi()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    pom {
        name = "inara-runtime"
        description = "Inara runtime"
    }
}
