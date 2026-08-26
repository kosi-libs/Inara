plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
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
    }
}
