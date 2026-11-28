plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.odyssey.vendor"
    defaultConfig {
        applicationId = "com.odyssey.vendor"
        val api = providers.gradleProperty("odyssey.apiUrl").getOrElse("https://carwashapis.prashanthsridhar.com")
        buildConfigField("String", "API_URL", "\"$api\"")
        buildConfigField("String", "TENANT", "\"carwash\"")
    }
}

dependencies {
    implementation(project(":feature:auth"))
    implementation(project(":feature:vendorops"))
    implementation(project(":vertical:carwash"))
    implementation(libs.activity.compose)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")   // camera scanning for the booking QR
}
