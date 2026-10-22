import org.gradle.api.publish.PublishingExtension
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

allprojects {
    group = "io.github.artofpaganini"
    version = "0.1.1"

    apply(plugin = "maven-publish")
}

// KMP and java-gradle-plugin modules self-publish; plain java modules need an explicit publication
subprojects {
    afterEvaluate {
        val publishing = extensions.findByType(PublishingExtension::class.java) ?: return@afterEvaluate
        val signingKey = System.getenv("ORG_GRADLE_PROJECT_signingKey")
            ?: providers.gradleProperty("signingKey").orNull
        if (signingKey != null) {
            val signingPassword = System.getenv("ORG_GRADLE_PROJECT_signingPassword")
                ?: providers.gradleProperty("signingPassword").orNull ?: ""
            val signingKeyId = (System.getenv("ORG_GRADLE_PROJECT_signingKeyId")
                ?: providers.gradleProperty("signingKeyId").orNull)?.takeIf { it.isNotBlank() }
            apply(plugin = "signing")
            extensions.configure(SigningExtension::class.java) {
                if (signingKeyId != null) {
                    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
                } else {
                    useInMemoryPgpKeys(signingKey, signingPassword)
                }
                sign(publishing.publications)
            }
        }
    }
}
