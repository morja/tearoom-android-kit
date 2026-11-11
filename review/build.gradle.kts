plugins {
    id("com.android.library")
}

// Apps depend on "one.tearoom:review"; the composite build substitutes this project (ADR-0001).
// One rule for asking for a Play rating (ADR-0006), headless: the settings row is the app's, the kit
// gives it openListing.
group = "one.tearoom"
version = "1.0.0"

android {
    namespace = "one.tearoom.review"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Google Play In-App Review (user decision, 11 November 2026). Plain review, not review-ktx: the
    // kit uses the Task callbacks, as with Billing (AGENTS.md rule 3).
    implementation("com.google.android.play:review:2.0.2")
    // ComponentActivity's lifecycle: the opening is counted on ON_START, the prompt waits for RESUMED.
    api("androidx.activity:activity:1.13.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
}
