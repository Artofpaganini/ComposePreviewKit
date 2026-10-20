import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.Sign

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

private val POM_URL = "https://github.com/ArtofPaganini/ComposePreviewKit"
private val POM_SCM_CONNECTION = "scm:git:github.com/ArtofPaganini/ComposePreviewKit.git"
private val POM_SCM_URL = "https://github.com/ArtofPaganini/ComposePreviewKit/tree/master"
private val LICENSE_NAME = "Apache-2.0"
private val LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
private val DEVELOPER_ID = "artofpaganini"
private val DEVELOPER_NAME = "Viktar Dabrou"

val kotlinVersion = libs.versions.kotlin.get()
val kspVersion = libs.versions.ksp.get()

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:$kspVersion")
}

gradlePlugin {
    plugins {
        create("previewkit") {
            id = "com.previewkit"
            implementationClass = "com.previewkit.gradle.PreviewKitPlugin"
        }
    }
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}


val pomUrl = "https://github.com/ArtofPaganini/ComposePreviewKit"

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "PreviewKit Gradle Plugin"
            description = "Gradle plugin wiring PreviewKit KSP generation into a module"
            inceptionYear = "2026"
            url = pomUrl
            licenses {
                license {
                    name = "Apache-2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0"
                    distribution = "repo"
                }
            }
            developers {
                developer {
                    id = "artofpaganini"
                    name = "Viktar Dabrou"
                }
            }
            scm {
                url = pomUrl
                connection = "scm:git:github.com/ArtofPaganini/ComposePreviewKit.git"
                developerConnection = "scm:git:github.com/ArtofPaganini/ComposePreviewKit.git"
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.withType<Sign>())
}

