// Versions follow the apps that include this build (Dahlia: AGP 9.4.1 with built-in Kotlin 2.4.20;
// the Kotlin JVM plugin for :testing and the Compose plugin for :unlock-ui must match that version).
plugins {
    id("com.android.library") version "9.4.1" apply false
    kotlin("jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
