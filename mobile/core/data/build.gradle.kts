plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:domain"))
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.test)
        }
        if (providers.gradleProperty("odyssey.ios").orNull == "true") {
            iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        }
    }
}
