plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    jvmToolchain(findProperty("jvm.toolchain")!!.toString().toInt())
    explicitApi()
}

dependencies {
    implementation(libs.kotlinPoet.ksp)
    implementation(libs.ksp.symbolProcessingApi)

    testImplementation(projects.inaraRuntime)

    testImplementation(libs.kctfork.ksp)
    // Pins the KSP2 implementation to match ksp-symbolProcessingApi's version - without this,
    // kctfork:ksp's own POM drags in an older impl than the API we compile against, an
    // API-newer-than-impl skew that risks AbstractMethodError.
    testImplementation(libs.ksp.aaEmbeddable)
    testImplementation(libs.kotlin.test.junit)
}

mavenPublishing {
    pom {
        name = "inara-processor"
        description = "Inara KSP processor"
    }
}
