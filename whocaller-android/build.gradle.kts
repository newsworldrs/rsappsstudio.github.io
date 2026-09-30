import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

/** Shared SDK levels. compileSdk/targetSdk track the latest stable platform supported by the AGP version above. */
object WhoCallerSdk {
    const val COMPILE = 35
    const val TARGET = 35
    const val MIN = 26
}
extra["whocaller.compileSdk"] = WhoCallerSdk.COMPILE
extra["whocaller.targetSdk"] = WhoCallerSdk.TARGET
extra["whocaller.minSdk"] = WhoCallerSdk.MIN

subprojects {
    // Android library defaults shared by every core/feature module.
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = WhoCallerSdk.COMPILE
            defaultConfig {
                minSdk = WhoCallerSdk.MIN
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions {
                unitTests.isIncludeAndroidResources = true
                unitTests.isReturnDefaultValues = true
            }
            lint {
                lintConfig = rootProject.file("lint.xml")
            }
        }
    }
    // Plain Kotlin/JVM modules.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
