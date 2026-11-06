plugins {
    id("com.android.library")
}

// Apps depend on "one.tearoom:permissions"; the composite build substitutes this project (ADR-0001).
// One flow for asking, losing and recovering a runtime permission (ADR-0005), headless: the surface
// is the app's or the kit's :permissions-ui.
group = "one.tearoom"
version = "1.0.0"

android {
    namespace = "one.tearoom.permissions"
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
    // ComponentActivity and the Activity Result APIs (user decision, 6 November 2026: Google's APIs, not Accompanist).
    api("androidx.activity:activity:1.13.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // @StringRes on PermissionTexts.
    api("androidx.annotation:annotation:1.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
}
