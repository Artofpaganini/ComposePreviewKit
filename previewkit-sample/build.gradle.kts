plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
    id("com.previewkit")
}

kotlin {
    jvmToolchain(21)

    jvm()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
        }
    }
}

previewToolkit {
    enabled.set(true)
    defaultBox.set("com.previewkit.sample.SampleBox")
    kspArg("previewkit.registry", "com.previewkit.sample.SamplePreviewRegistry")
}
