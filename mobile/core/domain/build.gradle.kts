plugins {
    alias(libs.plugins.androidLibrary)        // Android plugin first: the conventions in the root build file need it present.
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(libs.datetime)
        }
        commonTest.dependencies { implementation(libs.coroutines.test) }
    }
}
