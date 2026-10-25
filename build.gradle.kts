import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.kotlin.dsl.configure

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "org.kodein.di.inara"
    version = "0.2.0"
}

val mavenPublishPluginId = libs.plugins.mavenPublish.get().pluginId
subprojects {
    pluginManager.withPlugin(mavenPublishPluginId) {
        extensions.configure<MavenPublishBaseExtension> {
            pom {
                url = "https://github.com/kosi-libs/Inara"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                        distribution = "repo"
                    }
                }
                issueManagement {
                    system.set("Github")
                    url.set("https://github.com/kosi-libs/Inara/issues")
                }
                scm {
                    connection.set("https://github.com/kosi-libs/Inara.git")
                    url.set("https://github.com/kosi-libs/Inara")
                }
                developers {
                    developer {
                        name = "Kodein Koders"
                        email = "contact@kodein.net"
                        url = "https://kodein-koders.com"
                    }
                }
            }
        }
    }
}
