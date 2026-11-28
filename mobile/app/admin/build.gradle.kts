plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.odyssey.admin"
    defaultConfig {
        applicationId = "com.odyssey.admin"
        val api = providers.gradleProperty("odyssey.apiUrl").getOrElse("https://carwashapis.prashanthsridhar.com")
        buildConfigField("String", "API_URL", "\"$api\"")
        buildConfigField("String", "TENANT", "\"carwash\"")
    }
}

dependencies {
    implementation(project(":feature:auth"))
    implementation(project(":feature:adminops"))
    implementation(libs.activity.compose)
}
