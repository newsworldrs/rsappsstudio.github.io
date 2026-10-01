import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Firebase is optional: the Google Services and Crashlytics plugins are applied only when the
// developer has added app/google-services.json (never committed). Without it, WhoCaller runs with
// no-op analytics/crash reporting and guest-only accounts. See docs/README.md.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
}

/** Reads a config value from local.properties or the environment. Secrets never live in the repo. */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun config(name: String, default: String = ""): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: default).trim()
fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.rskusum.whocaller"
    compileSdk = rootProject.extra["whocaller.compileSdk"] as Int

    defaultConfig {
        applicationId = "com.rskusum.whocaller"
        minSdk = rootProject.extra["whocaller.minSdk"] as Int
        targetSdk = rootProject.extra["whocaller.targetSdk"] as Int
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "API_BASE_URL", config("WHOCALLER_API_BASE_URL").quoted())
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", config("WHOCALLER_GOOGLE_WEB_CLIENT_ID").quoted())
        buildConfigField("String", "PRIVACY_POLICY_URL", config("WHOCALLER_PRIVACY_URL", "https://www.rsappsstudio.com/whocaller/privacy").quoted())
        buildConfigField("String", "TERMS_URL", config("WHOCALLER_TERMS_URL", "https://www.rsappsstudio.com/whocaller/terms").quoted())
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasFirebaseConfig.toString())

        // AdMob: Google's public sample IDs are used unless real IDs are configured. They serve test ads only.
        manifestPlaceholders["admobAppId"] = config("WHOCALLER_ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")
        buildConfigField("String", "ADMOB_BANNER_ID", config("WHOCALLER_ADMOB_BANNER_ID", "ca-app-pub-3940256099942544/6300978111").quoted())
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Debug builds may use the in-memory development backend (fictional demo numbers only).
            buildConfigField("boolean", "ALLOW_DEV_BACKEND", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ALLOW_DEV_BACKEND", "false")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    lint {
        lintConfig = rootProject.file("lint.xml")
        checkDependencies = true
        abortOnError = true
        checkReleaseBuilds = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES")
    }
    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.database)
    implementation(projects.core.security)
    implementation(projects.core.ui)
    implementation(projects.core.permissions)
    implementation(projects.feature.home)
    implementation(projects.feature.callerid)
    implementation(projects.feature.callhistory)
    implementation(projects.feature.contacts)
    implementation(projects.feature.search)
    implementation(projects.feature.spam)
    implementation(projects.feature.blocking)
    implementation(projects.feature.sms)
    implementation(projects.feature.settings)
    implementation(projects.feature.profile)
    implementation(projects.feature.premium)
    implementation(projects.feature.dialer)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.config)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.kotlinx.coroutines.play.services)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
