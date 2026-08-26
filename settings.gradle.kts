dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Inara"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":inara-runtime")
include(":inara-processor")
include(":inara-tests")
