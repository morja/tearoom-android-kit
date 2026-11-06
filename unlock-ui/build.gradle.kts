plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Apps depend on "one.tearoom:unlock-ui"; the composite build substitutes this project (ADR-0001).
// The default purchase surface of the unlock flow (ADR-0004): Compose, Material 3, drawn from the
// host app's MaterialTheme. No theme of its own, no text of its own.
group = "one.tearoom"
version = "1.1.1"

android {
    namespace = "one.tearoom.unlock.ui"
    // Compose BOM 2026.09.00 compiles against 37; the BOM is the one the apps use, because Material 3's
    // sheet is an experimental API whose binary shape changes between versions (ADR-0004).
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":unlock"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    // The kit keeps its own guardrails: sizes only in UnlockStyle.kt, no colour literals at all.
    testImplementation(project(":testing"))
}
