plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The whole customer experience (routes, tabs, screens wiring). The Android app and the web app are thin hosts around it.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:auth"))
            api(project(":feature:discovery"))
            api(project(":feature:booking"))
            api(project(":vertical:carwash"))
            implementation("org.jetbrains.compose.ui:ui-backhandler:1.10.3")
        }
    }
}
