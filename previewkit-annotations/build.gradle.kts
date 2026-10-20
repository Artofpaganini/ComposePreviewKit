plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
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
        coordinates("io.github.artofpaganini", "previewkit-annotations", "0.1.0")
        publishToMavenCentral(automaticRelease = true)
        pom {
            name = "PreviewKit Annotations"
            description = "Compose preview annotations for PreviewKit"
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

val iosMinDeploymentTarget = libs.versions.iosMinDeploymentTarget.get()

kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "com.previewkit.annotations"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add(
                        "-Xoverride-konan-properties=osVersionMin.${target.konanTarget.name}=$iosMinDeploymentTarget",
                    )
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
        }
    }
}
