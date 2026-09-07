import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Phase 0. This app exists to answer one question on the real watch: does holding the
// button open us? It has no brain, no network, and no future. See docs/phase0-spike.md.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.claudewear.spike"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.claudewear.spike"
        minSdk = libs.versions.wearMinSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }

    // Two APKs that can be installed side by side, so the watch can be asked the question
    // both ways without reinstalling:
    //   assist — only an ACTION_ASSIST / VOICE_ASSIST activity. This is what Home Assistant
    //            ships on Wear and is the minimum that gets an app into the "Digital
    //            assistant app" picker.
    //   voice  — the same, plus a VoiceInteractionService. This is what a phone assistant
    //            needs; whether Samsung's press-and-hold reads it is the open question.
    flavorDimensions += "entry"
    productFlavors {
        create("assist") {
            dimension = "entry"
            applicationIdSuffix = ".assist"
            resValue("string", "app_name", "Spike (assist)")
        }
        create("voice") {
            dimension = "entry"
            applicationIdSuffix = ".voice"
            resValue("string", "app_name", "Spike (voice)")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        named("main") { java.srcDirs("src/main/kotlin") }
        named("voice") { java.srcDirs("src/voice/kotlin") }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}
