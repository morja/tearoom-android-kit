plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Apps depend on "one.tearoom:permissions-ui"; the composite build substitutes this project (ADR-0001).
// The default surface of the permission flow (ADR-0005, following ADR-0004): Compose, Material 3, drawn
// from the host app's MaterialTheme. No theme of its own, no text of its own.
group = "one.tearoom"
version = "1.0.0"

android {
    namespace = "one.tearoom.permissions.ui"
    // Compose BOM 2026.09.00 compiles against 37; the same BOM as the apps, like :unlock-ui (ADR-0004).
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
    api(project(":permissions"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // rememberLauncherForActivityResult and LocalActivity; LifecycleEventEffect for the refresh on resume.
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    testImplementation("junit:junit:4.13.2")
    // The kit keeps its own guardrails: sizes only in PermissionStyle.kt, no colour literals at all.
    testImplementation(project(":testing"))
}
