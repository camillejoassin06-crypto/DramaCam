# CLAUDE.md — DramaForge (Option A : zéro serveur, 2-3 utilisateurs, full gratuit)

## Contexte produit

Plateforme personnelle de dramas verticaux courts (épisodes 2-3 min, swipe
vertical). Produit 100 % original : marque, contenu et code. Ne jamais employer
la marque, les assets, les scripts ni les API d'un concurrent.
Usage STRICTEMENT personnel : 2-3 appareils, APK sideloadé, PERSONAL_MODE
(tout est débloqué — pas de monétisation, pas de Play Store).

## Architecture (ne pas la contredire)

- PAS de backend : le catalogue est le fichier `catalog/catalog.json` servi par
  GitHub Pages ; les vidéos sont sur Cloudflare R2 (bucket public, free 10 Go).
- App Android Kotlin + Jetpack Compose + Media3, `BuildConfig.PERSONAL_MODE=true`.
  Source de données unique : `StaticCatalogRepository` (parse catalog.json,
  cache Room). Progression/favoris : 100 % local (Room), aucune API.
- Pipeline TypeScript (Node 22) : génération HEBDOMADAIRE d'une série de
  50 épisodes via `claude -p` (headless, abonnement — PAS l'API Anthropic
  payante), TTS edge-tts/Kokoro, images SD local ou AI Studio, montage FFmpeg,
  HLS, upload R2, mise à jour de catalog.json + commit. C'est la seule voie qui
  produise des épisodes **jouables**.
- Génération de texte sur l'appareil (`app/.../generation/`) : l'utilisateur
  saisit sa propre clé Anthropic dans les Paramètres, et l'app génère QUOTIDIENNEMENT
  les scripts d'une série complète via le SDK officiel. Sorties texte
  uniquement — ni voix, ni images, ni vidéo : FFmpeg et edge-tts ne tournent pas
  sur un téléphone. Les brouillons restent locaux et n'entrent pas dans
  `catalog.json`.

## Règles absolues

- Coûts : le pipeline CI reste à coût nul et n'utilise que `claude -p`
  (abonnement). N'y introduire aucun service payant.
  **Exception décidée le 2026-10-16** : la génération de texte sur l'appareil
  utilise l'API Anthropic, facturée à l'usage sur la clé de l'utilisateur. Elle
  est limitée à l'app, désactivée par défaut, et ne doit jamais devenir une
  dépendance du pipeline ni de la CI. Aucun autre service payant n'est autorisé.
- Une clé API saisie par l'utilisateur se stocke chiffrée
  (`EncryptedSharedPreferences`) et ne part que vers `api.anthropic.com`. Elle
  n'est jamais écrite dans le code, les logs, un fichier versionné ni l'APK.
- PERSONAL_MODE : jamais réactiver wallet/billing/parrainage/VIP sans demande
  explicite. Aucun code de dépense de coins ne doit réapparaître.
- Contenu original obligatoire : ne jamais réutiliser nom, logo, texte ou
  visuel d'une marque existante. Les titres générés passent par
  `excludedTerms` de catalog.json. Une revue de sûreté et de droits est requise
  avant rendu/publication.
- Aucune clé/secret en clair dans le repo (public !) : GitHub Secrets
  uniquement, jamais dans l'APK.
- Kotlin : Compose + Hilt + Media3, pas de XML layouts. Le build debug doit
  passer avant tout commit feature.
- Pipeline : sorties JSON strictes validées par zod (`pipeline/src/schemas/`),
  STYLE_LOCK immuable pour la cohérence des personnages.
- App : toute sortie du modèle est validée avant usage
  (`generation/GenerationModels.kt`, en miroir des schémas zod), avec
  1 génération + 2 régénérations maximum. Les prompts ne sont PAS dupliqués en
  Kotlin : ils sont copiés depuis `pipeline/src/prompts/` au build.
- `etudedeploiement/` est hors périmètre : ne pas lire, modifier ni inclure
  dans CI.
- Commits conventional : `feat(app):` | `feat(pipeline):` | `fix(app):` |
  `chore(ci):` | `feat(catalog):`

## Commandes

- Install racine :   `npm install`
- Typecheck :        `npm run typecheck`           (tsc --noEmit sur pipeline/)
- Tests :            `npm test`
- Valider catalogue : `npm run catalog:validate`    (zod, ~1 s)
- Pipeline dry-run : `npm run generate:dry`         (3 épisodes, images placeholder)
- App debug :        `cd app && ./gradlew assembleDebug`
  **JAVA_HOME doit pointer sur le JDK 21 d'Android Studio** :
  `C:\Program Files\Android\Android Studio\jbr`. Le JDK 26 installé sur le
  système fait échouer Gradle 8.9 (erreur « 26.0.1 », version non supportée).
- App release :      `cd app && ./gradlew assembleRelease` (R8 + réduction des
  ressources, signé avec `app/dramaforge-release.jks`). Le keystore et ses mots
  de passe vivent dans `app/local.properties`, hors du dépôt : les perdre
  interdit toute mise à jour d'une app déjà installée.
- App lint :         `cd app && ./gradlew lintDebug`
- App tests :        `cd app && ./gradlew testDebugUnitTest`
- App install :      `adb install -r app/app/build/outputs/apk/debug/app-debug.apk`
- Pipeline complet : déclencher le workflow GitHub (`weekly-drama.yml`) — ne
  JAMAIS lancer la génération complète en local sans accord explicite
  (fair-use). `.claude/settings.json` la refuse par défaut.

## Points d'attention

- R2 free = 10 Go (~10-12 séries 480p) : `pipeline/src/publish.ts` vérifie la
  taille du bucket et purge la plus ancienne série si > 9 Go.
- Fair-use Claude Code : 1 série/semaine max via `claude -p`. Par épisode,
  1 génération + 2 régénérations maximum
  (`MAX_ATTEMPTS_PER_EPISODE = 3` dans `pipeline/src/config.ts`).
- `catalog.json` est l'unique contrat app↔contenu : toute modification du
  schéma met à jour `catalog/schema.json`, `pipeline/src/schemas/catalog.ts`
  et `StaticCatalogRepository` dans le même commit.
