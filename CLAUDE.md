# CLAUDE.md — JeDrama

## Contexte produit

Studio personnel de micro-dramas verticaux (épisodes 2-3 min, swipe vertical)
qui tourne entièrement sur un téléphone Android. Un seul utilisateur, APK
sideloadée, aucune monétisation. Fork de DramaCam / DramaForge : produit
100 % original, ne jamais employer la marque, les assets ou les API d'un
service existant.

## Architecture (ne pas la contredire)

- PAS de serveur, PAS de backend, PAS de stockage distant. Les vidéos, scripts
  et la progression de lecture restent dans `filesDir` du téléphone.
- Génération quotidienne par WorkManager (`generation/DailySeriesWorker.kt`),
  en avant-plan : texte (Claude ou Gemini) -> voix (TTS Android) -> images
  (Pollinations ou hors ligne) -> FFmpeg embarqué -> `.mp4` + `.vtt`.
- FFmpeg est un exécutable compilé par `scripts/build-ffmpeg-android.sh` et
  rangé en `jniLibs/<abi>/libffmpeg.so` (jamais commité). Ajouter un filtre ou
  un codec à une commande = l'ajouter dans `COMPONENTS` du script, sinon il
  manquera sur le téléphone. `FfmpegCommandsTest` exécute les vraies commandes
  sur le FFmpeg hôte compilé avec la même liste (`HOST=1`).
- Les lignes de commande FFmpeg sont des fonctions pures (`media/EpisodeRenderer.kt`,
  `FfmpegCommands`) : les garder pures et testées.
- Kotlin + Jetpack Compose + Media3. Pas de XML layouts.

## Règles absolues

- Coût : par défaut gratuit (Gemini offre gratuite, Pollinations, TTS du
  téléphone) ou quasi gratuit (Claude Haiku 5.5, effort `low`). Aucun service
  payant ajouté sans demande explicite. Garde-fous : `MAX_ATTEMPTS` = 3 par
  sortie du modèle, `episodesPerDay`, `MAX_NEW_SERIES_PER_RUN`.
- Clés API : `EncryptedSharedPreferences` uniquement, envoyées seulement à
  leur fournisseur. Jamais dans le code, les logs, un fichier versionné ni l'APK.
- Keystore de release et mots de passe : `app/local.properties` ou GitHub
  Secrets. Jamais commités (`*.jks` est ignoré).
- Toute sortie du modèle est validée (`generation/GenerationModels.kt`) avant
  usage. Les prompts vivent dans `app/app/src/main/assets/prompts/`.
- `STYLE_LOCK` (`media/Images.kt`) est immuable : il garde la cohérence
  visuelle des séries existantes.
- Commits conventional : `feat(app):` | `fix(app):` | `feat(media):` |
  `chore(ci):` | `chore(ffmpeg):`

## Commandes

- FFmpeg Android : `ANDROID_NDK_HOME=... scripts/build-ffmpeg-android.sh`
- FFmpeg hôte (tests) : `BUILD_DIR=$PWD/build/ffmpeg-host HOST=1 scripts/build-ffmpeg-android.sh`
- Tests : `cd app && FFMPEG_HOST=$PWD/../build/ffmpeg-host/host/bin/ffmpeg ./gradlew testDebugUnitTest`
- APK debug : `cd app && ./gradlew assembleDebug`
- APK release : `cd app && ./gradlew assembleRelease` (exige libffmpeg.so)
- Install : `adb install -r app/app/build/outputs/apk/release/app-release.apk`
- CI : `.github/workflows/android.yml` (tag `v*` -> Release GitHub avec l'APK)
