plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.odyssey.customer"
    defaultConfig {
        applicationId = "com.odyssey.customer"
        // 10.0.2.2 is the host machine as seen from the Android emulator. Override: -Podyssey.apiUrl=https://api.example.com
        val api = providers.gradleProperty("odyssey.apiUrl").getOrElse("http://10.0.2.2:8080")
        buildConfigField("String", "API_URL", "\"$api\"")
        buildConfigField("String", "TENANT", "\"carwash\"")
    }
}

dependencies {
    implementation(project(":feature:auth"))
    implementation(project(":feature:discovery"))
    implementation(project(":feature:booking"))
    implementation(project(":vertical:carwash"))
    implementation(libs.activity.compose)
}
