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
include(":core:testing")

// Features. Each owns its screens and ViewModels; the app module wires navigation.
include(":feature:home")
include(":feature:callerid")
include(":feature:callhistory")
include(":feature:contacts")
include(":feature:search")
include(":feature:spam")
include(":feature:blocking")
include(":feature:sms")
include(":feature:settings")
include(":feature:profile")
include(":feature:premium")
include(":feature:dialer")
include(":feature:postcall")
