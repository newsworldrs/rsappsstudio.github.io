plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Shared fakes for unit tests across modules (test-only dependency).
dependencies {
    api(projects.core.domain)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
}
