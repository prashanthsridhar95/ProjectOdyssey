import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
}

// Conventions shared by every module, so each module's build file only lists what is special about it.
// Modules the customer web app (Kotlin/Wasm) is built from.
val webModules = setOf(":core:domain", ":core:data", ":core:ui", ":feature:auth", ":feature:discovery", ":feature:booking", ":vertical:carwash", ":feature:customerapp")

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        if (project.path == ":app:customer-web") return@withId   // web-only app: configured in its own build file
        extensions.configure<KotlinMultiplatformExtension> {
            if (project.path in webModules) {
                @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }
            }
            androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
            jvm()
            // iOS targets are opt-in: ./gradlew build -Podyssey.ios=true (needs Xcode and the Kotlin/Native toolchain)
            if (providers.gradleProperty("odyssey.ios").orNull == "true") {
                iosArm64()
                iosSimulatorArm64()
            }
            sourceSets.getByName("commonTest").dependencies { implementation(kotlin("test")) }
        }
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    }
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            namespace = "com.odyssey.mobile" + project.path.replace(":", ".").replace("-", "")
            compileSdk = 36
            defaultConfig { minSdk = 26 }
            compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = 36
            defaultConfig { minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "0.1.0" }
            compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            buildFeatures { buildConfig = true }
            packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        }
    }
}

// Browser tests need a headless Chrome; the unit tests that matter run on the JVM.
subprojects { tasks.matching { it.name.startsWith("wasmJs") && it.name.endsWith("Test") }.configureEach { enabled = false } }
