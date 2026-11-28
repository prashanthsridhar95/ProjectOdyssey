import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The customer app in a browser: the same screens as the Android app, compiled to WebAssembly.
// Build the bundle with: ./gradlew :app:customer-web:wasmJsBrowserDistribution
kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "customer"
        browser { commonWebpackConfig { outputFileName = "customer.js" } }
        binaries.executable()
    }
    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":feature:customerapp"))
        }
    }
}
