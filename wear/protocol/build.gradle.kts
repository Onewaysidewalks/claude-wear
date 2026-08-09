import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// A plain Kotlin/JVM library, not an Android one: the protocol has nothing Android about
// it, and the contract tests run as fast unit tests.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// The fixtures live outside the Gradle build, because both languages assert against them.
val goldenDir = rootProject.file("../protocol/golden")

tasks.test {
    useJUnit()
    systemProperty("protocol.golden.dir", goldenDir.absolutePath)
    // Without this the fixtures are invisible to up-to-date checking, and editing one
    // would leave the contract test happily cached against the previous contents.
    inputs.dir(goldenDir).withPropertyName("goldenFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging { showStandardStreams = true }
}
