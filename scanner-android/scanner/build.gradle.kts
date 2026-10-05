plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("maven-publish")
}

/** Library version; CI / JitPack can override it with -PscannerVersion=... */
val scannerVersion: String = (findProperty("scannerVersion") as String?) ?: "1.5.0"

android {
    namespace = "com.rskusum.scanner"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        aarMetadata {
            minCompileSdk = 35
        }
    }

    buildTypes {
        release {
            // The host app shrinks; consumer-rules.pro keeps what the native code needs.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // The models are memory-mapped straight from the APK.
        noCompress += "tflite"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    val cameraX = "1.4.1"
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    api(composeBom)
    api("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    api("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Computer vision: document edge detection, perspective correction, enhancement, QR.
    // Exposed as api: the advanced API (DocumentDetector, ImageEnhancer) takes OpenCV Mats.
    api("org.opencv:opencv:4.12.0")

    // On-device models (document edges, text orientation). Plain TFLite interpreter, no ML Kit.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // On-device OCR (Tesseract LSTM, Apache 2.0) - published on JitPack.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            // JitPack coordinates of this GitHub repository: com.github.newsworldrs:rsappsstudio.github.io:<tag>
            groupId = (findProperty("scannerGroup") as String?) ?: "com.github.newsworldrs"
            artifactId = (findProperty("scannerArtifact") as String?) ?: "rsappsstudio.github.io"
            version = scannerVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("RS Kusum Scanner")
                description.set("Android document scanner library: auto capture, auto crop, filters, eraser, PDF. Jetpack Compose + CameraX + OpenCV, no ML Kit.")
                url.set("https://github.com/newsworldrs/rsappsstudio.github.io")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("rskusum")
                        name.set("RS KUSUM")
                        email.set("rskusum@rsappsstudio.com")
                        organization.set("RS Apps Studio")
                        organizationUrl.set("https://www.rsappsstudio.com")
                    }
                }
                scm {
                    url.set("https://github.com/newsworldrs/rsappsstudio.github.io")
                }
            }
        }
    }
}
