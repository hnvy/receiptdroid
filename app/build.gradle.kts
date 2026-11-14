import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing and version, given through the same environment variables as Ledgedroid's release
// workflow. Without them (eg building on your own machine) the release build is signed with the debug key
// and keeps the version below.
val releaseKeystore: String? = System.getenv("LEDGEDROID_KEYSTORE")?.takeIf { it.isNotBlank() }

// The OCR engine, as gradle.properties says (receipts.ocr): its code is in src/ocr-<engine>, and this is its
// library. Only the one chosen is built into the app.
val ocrLibraries = mapOf(
    // The Latin-script model inside the app: nothing downloaded, no Google Play services. Other scripts are
    // separate artifacts (text-recognition-chinese, -japanese…); not the play-services-mlkit ones, which
    // download their model.
    "mlkit" to "com.google.mlkit:text-recognition:16.0.1",
    // Tesseract for Android, from JitPack (see settings.gradle.kts). Its language data is in src/ocr-tesseract/assets.
    "tesseract" to "cz.adaptech.tesseract4android:tesseract4android:4.9.0",
)
val ocrEngine: String = providers.gradleProperty("receipts.ocr").orNull?.trim()?.lowercase() ?: "mlkit"
if (ocrEngine !in ocrLibraries) {
    throw GradleException("receipts.ocr in gradle.properties is \"$ocrEngine\": it can be ${ocrLibraries.keys.joinToString(" or ")}")
}

android {
    namespace = "app.receiptdroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.receiptdroid"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("LEDGEDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("LEDGEDROID_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LEDGEDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LEDGEDROID_KEY_ALIAS")
                keyPassword = System.getenv("LEDGEDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
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
    sourceSets {
        getByName("main") {
            java.srcDir("src/ocr-$ocrEngine/java")
            assets.srcDir("src/ocr-$ocrEngine/assets")
        }
    }
    androidResources {
        // Tesseract's language data, kept as it is in the APK: it's compressed already, and copied out whole.
        noCompress += "traineddata"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Reads the text on the photo, on the phone (see ocrLibraries above). If it asks for network access, the
    // manifest takes it away again, and checkNoNetwork below makes sure of it.
    implementation(ocrLibraries.getValue(ocrEngine))

    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------------------- no network
//
// The app must work, and only work, offline. Every build looks at the final manifest, with every library's
// merged in, and fails if anything in it would let the app use the network.

/** Permissions that open the network, or tell the app about it. */
val networkPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_STATE",
)

abstract class CheckNoNetwork : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val forbidden: ListProperty<String>

    @TaskAction
    fun check() {
        val text = manifest.get().asFile.readText()
        // <uses-permission android:name="…"/>, and its -sdk-23 form, with the attributes in any order.
        val asked = Regex("<uses-permission(?:-sdk-23)?\\b[^>]*?android:name\\s*=\\s*\"([^\"]+)\"")
            .findAll(text).map { it.groupValues[1] }.toSet()
        val found = forbidden.get().filter { it in asked }
        if (found.isNotEmpty()) {
            throw GradleException(
                "The app must not use the network, but its merged manifest asks for: ${found.joinToString()}. " +
                    "A library brought it in: remove it in AndroidManifest.xml with tools:node=\"remove\". " +
                    "(${manifest.get().asFile})",
            )
        }
        logger.lifecycle("No network permissions in ${manifest.get().asFile.name} (${asked.size} permissions in all)")
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<CheckNoNetwork>("check${name}NoNetwork") {
            group = "verification"
            description = "Fails if the ${variant.name} build's merged manifest lets the app use the network."
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            forbidden.set(networkPermissions)
        }
        // Before every APK or bundle of this variant, and with ./gradlew check.
        tasks.matching { it.name == "assemble$name" || it.name == "bundle$name" || it.name == "check" }
            .configureEach { dependsOn(check) }
    }
}

