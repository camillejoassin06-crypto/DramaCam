import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProperties.getProperty(name)

val releaseStore = signingValue("RELEASE_STORE_FILE")?.let(rootProject::file)
// Sans keystore, le build release reste possible mais non signe : mieux vaut un
// artefact explicitement non signe qu'un echec opaque.
val hasReleaseSigning = releaseStore?.exists() == true

// FFmpeg est compile a part (scripts/build-ffmpeg-android.sh) : une APK sans lui
// ne saurait rien monter, donc le build release refuse de partir sans.
val ffmpegBinary = file("src/main/jniLibs/arm64-v8a/libffmpeg.so")

android {
    namespace = "com.jedrama.fork"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jedrama.fork"
        minSdk = 24
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "10").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"

        // FFmpeg n'est compile que pour arm64 : la quasi-totalite des telephones
        // depuis 2017. Ajouter x86_64 dans ABIS du script pour un emulateur.
        ndk { abiFilters += listOf("arm64-v8a") }

        // Usage personnel : APK sideloadee, aucune monetisation.
        buildConfigField("boolean", "PERSONAL_MODE", "true")

        // Hilt : application ID pour le GeneratedTestRunner
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStore
                storePassword = signingValue("RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    // Java et Kotlin doivent viser la meme version, sinon le build echoue sur
    // une incoherence de jvmTarget.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Le SDK Anthropic expose Optional, java.time et les Streams dans son
        // API publique : sans desugaring, rien de tout cela n'existe en API 24.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // L'executable FFmpeg doit etre extrait sur le disque (nativeLibraryDir)
        // pour pouvoir etre lance : pas de chargement direct depuis l'APK.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation(platform("androidx.hilt:hilt-bom:2.50"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Lecteur vertical : ExoPlayer + sous-titres WebVTT.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Generation quotidienne : SDK Anthropic (Gemini via REST) + WorkManager + stockage chiffre.
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    // org.json est seulement stubbe dans les tests unitaires Android : il faut
    // une vraie implementation pour exercer le parsing.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.google.dagger:hilt-android-compiler:2.50")
    kaptTest("com.google.dagger:hilt-android-compiler:2.50")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.50")
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(ffmpegBinary.exists()) {
            "FFmpeg absent : lancez scripts/build-ffmpeg-android.sh (ANDROID_NDK_HOME requis)."
        }
    }
}
