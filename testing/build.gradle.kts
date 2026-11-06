plugins {
    kotlin("jvm")
}

// Apps depend on "one.tearoom:testing" in their unit tests; the composite build substitutes this
// project (ADR-0001). Pure JVM: no Android, no test framework, only java.nio and javax.xml (ADR-0003).
group = "one.tearoom"
version = "1.0.0"

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
