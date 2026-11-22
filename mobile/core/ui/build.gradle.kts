plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    sourceSets {
        androidMain.dependencies { implementation("com.google.zxing:core:3.5.3") }
        jvmMain.dependencies { implementation("com.google.zxing:core:3.5.3") }
        commonMain.dependencies {
            api(project(":core:data"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api(compose.components.resources)
            api("com.composables:icons-lucide-cmp:2.2.1")
            api(libs.lifecycle.viewmodel)
            api(libs.lifecycle.viewmodel.compose)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.odyssey.ui.res"
}
