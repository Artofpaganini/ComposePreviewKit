plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

allprojects {
    group = "io.github.artofpaganini.previewkit"
    version = "0.1.0"

    apply(plugin = "maven-publish")
}

// KMP and java-gradle-plugin modules self-publish; plain java modules need an explicit publication
subprojects {
    afterEvaluate {
        val publishing = extensions.findByType(PublishingExtension::class.java) ?: return@afterEvaluate
        if (publishing.publications.isEmpty()) {
            publishing.publications.create<MavenPublication>("previewkitMaven") {
                from(components.findByName("java"))
            }
        }
    }
}
