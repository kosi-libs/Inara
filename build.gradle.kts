plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "org.kodein.di.inara"
    version = "0.1.0"
}
