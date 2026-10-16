import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Identifiants de signature lus dans local.properties, qui est hors du depot
// (cf. .gitignore). Aucun secret n'entre dans le code source ni dans l'APK.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
/**
 * Les prompts et la liste de termes exclus ne sont pas dupliques en Kotlin :
 * ils sont copies depuis pipeline/ et catalog/ a chaque build, dans un dossier
 * genere declare comme source d'assets. Une seule source de verite, et toute
 * modification d'un prompt s'applique a l'app sans copier-coller.
 */
val sharedAssets: Provider<Directory> = layout.buildDirectory.dir("generated/sharedAssets")

val copySharedAssets by tasks.registering(Copy::class) {
    description = "Copie les prompts du pipeline et le catalogue dans les assets."
    into(sharedAssets)
    from(rootProject.file("../pipeline/src/prompts")) {
        include("*.md")
        into("prompts")
    }
    from(rootProject.file("../catalog")) {
        include("catalog.json")
        into("catalog")
    }
}

val releaseStoreName: String? = localProperties.getProperty("RELEASE_STORE_FILE")
val releaseStore = releaseStoreName?.let(rootProject::file)
// Sans keystore (autre poste, CI), le build release reste possible mais non
// signe : mieux vaut un artefact explicitement non signe qu'un echec opaque.
val hasReleaseSigning = releaseStore?.exists() == true

android {
    namespace = "com.dramaforge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dramaforge.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        // PERSONAL_MODE : APK sideloade, tout est accessible, aucune
        // monetisation. Ne jamais passer a false sans demande explicite
        // (cf. CLAUDE.md).
        buildConfigField("boolean", "PERSONAL_MODE", "true")

        // Unique source de donnees : catalog.json servi par GitHub Pages.
        // Aucun secret ici : l'URL est publique et l'APK ne contient rien d'autre.
        buildConfigField(
            "String",
            "CATALOG_URL",
            "\"https://example-placeholder.github.io/dramaforge/catalog.json\"",
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStore
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
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

    // On declare la TACHE, pas le dossier : Gradle en deduit la dependance
    // pour tout consommateur des assets (fusion, lint, packaging). Declarer le
    // chemin seul laissait lint lire un dossier sans savoir qui le produit.
    sourceSets["main"].assets.srcDir(copySharedAssets)

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Generation quotidienne : SDK officiel Anthropic + job periodique +
    // stockage chiffre de la cle saisie par l'utilisateur.
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    // org.json est seulement stubbe dans les tests unitaires Android : il faut
    // une vraie implementation pour exercer le parsing.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
