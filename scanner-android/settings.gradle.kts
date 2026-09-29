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
        maven("https://jitpack.io") // Tesseract4Android (OCR)
    }
}

rootProject.name = "RSKusumScanner"
include(":scanner") // the library
include(":app")     // the standalone scanner app / demo
