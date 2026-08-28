plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ksp.symbolProcessingApi)
    implementation(libs.kotlinPoet.ksp)

    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(projects.inaraRuntime)
}

mavenPublishing {
    pom {
        name = "inara-processor"
        description = "Inara KSP processor"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
    }
}
