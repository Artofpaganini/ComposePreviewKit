plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

// PreviewKit Maven Central publication setup (shared boilerplate)
val POM_URL = "https://github.com/ArtofPaganini/ComposePreviewKit"
val POM_SCM_CONNECTION = "scm:git:github.com/ArtofPaganini/ComposePreviewKit.git"
val POM_SCM_URL = "https://github.com/ArtofPaganini/ComposePreviewKit/tree/master"
val LICENSE_NAME = "Apache-2.0"
val LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
val DEVELOPER_ID = "artofpaganini"
val DEVELOPER_NAME = "Viktar Dabrou"

    mavenPublishing {
        coordinates("io.github.artofpaganini", "previewkit-config-api", version.toString())
        publishToMavenCentral(automaticRelease = true)
        pom {
            name = "PreviewKit Config API"
            description = "SPI contracts for PreviewKit mock providers"
            inceptionYear = "2026"
            url = POM_URL
            licenses {
                license {
                    name = LICENSE_NAME
                    url = LICENSE_URL
                    distribution = "repo"
                }
            }
            developers {
                developer {
                    id = DEVELOPER_ID
                    name = DEVELOPER_NAME
                }
            }
            scm {
                url = POM_URL
                connection = POM_SCM_CONNECTION
                developerConnection = POM_SCM_CONNECTION
            }
        }
    }

dependencies {
    compileOnly(libs.ksp.api)
    implementation(libs.kotlinpoet)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.withType<Sign>())
}
