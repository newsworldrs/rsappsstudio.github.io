pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "WhoCaller"

include(":app")

// Pure Kotlin/JVM layers (no Android dependency): models, domain logic, networking.
include(":core:model")
include(":core:common")
include(":core:domain")
include(":core:network")

// Android core layers.
include(":core:database")
include(":core:data")
include(":core:ui")
include(":core:permissions")
include(":core:security")

// Features are included as they are implemented (see below).
