plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    // Google's maintained phone-number metadata and parsing rules (no hand-written country rules).
    api(libs.libphonenumber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
