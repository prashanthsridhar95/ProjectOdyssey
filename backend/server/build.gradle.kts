plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("com.odyssey.server.MainKt") }
dependencies {
    implementation(project(":core"))
    implementation(project(":pack-carwash"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pg)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.java.jwt)
    implementation(libs.logback)
    runtimeOnly(libs.h2)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
