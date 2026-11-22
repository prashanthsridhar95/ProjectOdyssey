// Car wash vertical pack. Depends on the core only; the core never depends on this module.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core"))
    testImplementation(libs.kotlin.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
