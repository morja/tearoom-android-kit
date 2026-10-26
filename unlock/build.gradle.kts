plugins {
    id("com.android.library")
}

// Apps depend on "one.tearoom:unlock"; the composite build substitutes this project (ADR-0001).
group = "one.tearoom"
version = "1.0.0"

android {
    namespace = "one.tearoom.unlock"
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
    // Plain billing: the library uses callbacks, not the Kotlin extensions of billing-ktx.
    implementation("com.android.billingclient:billing:9.1.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
}
