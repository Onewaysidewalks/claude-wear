// Types shared by the phone and the watch. Pure Kotlin: no Android here, so the tests run on the
// JVM in a second and the contract check against gateway/fixtures needs no emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Bytecode 17 without provisioning a toolchain: the JDK 21 that runs Gradle does the compiling.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // The gateway's golden fixtures are the cross-language contract test.
    systemProperty("gateway.fixtures", rootProject.file("../gateway/fixtures/v1").absolutePath)
}
