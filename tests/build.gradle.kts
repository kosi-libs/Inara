import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())

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
    // No macosX64: Kotlin deprecated it in 2.3.20 and will remove it. iosX64 above is the Intel
    // iOS simulator (still supported), not Intel macOS - the two are unrelated.
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

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
        }
        commonMain.dependencies {
            implementation(projects.inaraRuntime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", projects.inaraProcessor)
}

// Every compilation - and every per-target KSP task, which also reads commonMain as part of its
// merged source set even though it has no processor registered - consumes the KSP-generated
// commonMain sources, so all of them must wait for KSP to run.
tasks.matching { it.name != "kspCommonMainKotlinMetadata" }.configureEach {
    if (name.startsWith("ksp") || this is KotlinCompilationTask<*>) {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
