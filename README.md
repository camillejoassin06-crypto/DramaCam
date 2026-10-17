# JeDrama-Fork

Studio personnel de micro-dramas verticaux générés par IA, entièrement sur appareil Android — **fork open source** de [JeDrama](https://github.com/camillejoassin06-crypto/DramaCam).

## Concept

Chaque jour, l'app écrit, fait parler, illustre et monte de nouveaux épisodes de 2 à 3 minutes, puis les range dans une bibliothèque locale lisible au swipe. **Pas de serveur, pas de compte, tout reste sur le téléphone.**

Contrairement aux plateformes de streaming (type mydramawave.com) qui diffusent du contenu sous licence, JeDrama-Fork génère du contenu **100 % original** via des modèles de langage (Claude ou Gemini), de la synthèse vocale Android (TTS), des images IA (Pollinations ou hors-ligne), et un montage FFmpeg embarqué.

## Améliorations du fork

| Domaine | Amélioration |
|---------|-------------|
| **Architecture** | Repository pattern, Hilt DI, StateFlow pour l'état UI réactif |
| **Performance** | Cache disque des images, rendu parallèle, bitmap LRU |
| **Fiabilité** | Reprise granulaire après interruption, retry avec backoff |
| **UI/UX** | Navigation basale 3 onglets, recherche, favoris, reprise de lecture |
| **Open source** | Licence MIT, CI/CD GitHub Actions, CONTRIBUTING, CHANGELOG |

## Installation

### Depuis les releases GitHub

1. Téléchargez JeDrama-Fork-<version>-release.apk depuis [Releases](https://github.com/votre-repo/JeDrama-Fork/releases)
2. Sur le téléphone, autorisez l'installation depuis des sources inconnues
3. Ouvrez l'APK

### Depuis le code source

`ash
# Cloner le dépôt
git clone https://github.com/votre-repo/JeDrama-Fork.git
cd JeDrama-Fork

# Configurer le SDK Android
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK_HOME=/ndk/27.2.12479018

# Compiler FFmpeg (une seule fois)
chmod +x scripts/build-ffmpeg-android.sh
ANDROID_NDK_HOME=/ndk/27.2.12479018 scripts/build-ffmpeg-android.sh

# Construire et tester
cd app
./gradlew testDebugUnitTest assembleRelease
`

## Configuration

1. Dans l'app > Paramètres :
   - Choisissez **Gemini (gratuit)** et collez une clé créée sur [Google AI Studio](https://aistudio.google.com/apikey) (sans activer la facturation), ou **Claude** avec une clé Anthropic
   - Testez la clé, puis touchez « Générer maintenant »
2. Activez « Générer chaque jour » pour une série automatique
3. Autorisez les notifications (nécessaire pour les générations > 10 min)

## Coût

| Poste | Solution | Coût |
|-------|----------|------|
| Texte | Gemini (Google AI Studio gratuit) | 0 € |
| Texte (alternative) | Claude Haiku 5.5 | ≈ 1-3 centimes / 5 épisodes |
| Voix | Synthèse vocale Android (TTS) | 0 € |
| Images | Pollinations (gratuit, sans clé) ou hors-ligne | 0 € |
| Montage, stockage | FFmpeg embarqué + stockage local | 0 € |

## Arborescence

`
app/app/src/main/
  assets/prompts/        prompts (concept, bible, épisode, revue)
  assets/excluded-terms.txt  marques interdites dans les titres
  java/com/jedrama/fork/
    di/                  Injection de dépendances (Hilt)
    generation/          Modèles de texte, validation, worker quotidien
    library/             Bibliothèque, dépôts, reprise de lecture
    media/               FFmpeg, TTS, images, sous-titres, montage, cache
    settings/            Stockage chiffré des clés API
    ui/                  Écrans Compose : accueil, lecteur, séries, paramètres
scripts/build-ffmpeg-android.sh
.github/workflows/android.yml
`

## Licence

[MIT](LICENSE) — voir le fichier LICENSE pour les détails.

## Contribuer

Voir [CONTRIBUTING.md](CONTRIBUTING.md).

## Changelog

Voir [CHANGELOG.md](CHANGELOG.md).