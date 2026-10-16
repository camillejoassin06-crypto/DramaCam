# DramaForge — Option A (zéro serveur, usage personnel, coût 0 €)

Plateforme personnelle de micro-dramas verticaux originaux : épisodes de 2 à
3 minutes, lecture au swipe, 2-3 appareils, APK sideloadé. Produit original —
aucune marque, aucun asset, aucun script ni API d'un service existant.

`CLAUDE.md` est le contrat du projet et prime sur ce README.
`etudedeploiement/` est hors périmètre.

## Deux voies de génération

| | Pipeline CI (hebdomadaire) | App (quotidien) |
|---|---|---|
| Déclencheur | cron GitHub Actions | WorkManager sur l'appareil |
| Modèle | `claude -p` (abonnement) | API Anthropic, clé de l'utilisateur |
| Coût | 0 € | facturé à l'usage |
| Produit | épisodes **jouables** (vidéo, voix, HLS) | **scripts texte** uniquement |
| Destination | R2 + `catalog.json` | brouillons locaux |

La seconde voie existe parce qu'un téléphone ne peut pas produire de vidéo :
FFmpeg, edge-tts et la génération d'images ne tournent pas sur Android. Elle est
désactivée par défaut et n'a aucun effet sur le catalogue.

## Architecture

Il n'y a pas de backend. Trois pièces, et un seul contrat entre elles.

```
pipeline/  (Node 22 + TypeScript)        catalog/              app/ (Android)
 claude -p -> zod -> edge-tts            catalog.json  <------  StaticCatalogRepository
 -> images -> FFmpeg -> HLS              (GitHub Pages)         + cache Room local
 -> upload R2 -> réécrit catalog.json
                                         Cloudflare R2  <-----  Media3 (HLS + .vtt)
                                         (bucket public)
```

- **`catalog/catalog.json`** est l'unique interface app ↔ contenu, servie par
  GitHub Pages et validée par `catalog/schema.json` + les schémas zod.
  Il ne contient **aucun** champ de prix ni de verrou : PERSONAL_MODE est une
  propriété du format, pas une option de l'app.
- **Vidéos** sur Cloudflare R2 (offre gratuite 10 Go). Au-delà de 9 Go,
  `pipeline/src/publish.ts` purge la série la plus ancienne.
- **Génération de texte** par `claude -p` headless (abonnement Claude Code).
  L'API Anthropic facturée à l'usage est interdite, comme tout service payant.
- **Progression et favoris** 100 % locaux (Room). Aucun compte, aucune
  télémétrie, aucune monétisation.

## Démarrage

```powershell
npm install
npm run typecheck
npm test
npm run catalog:validate
npm run generate:dry     # 3 épisodes, sans modèle ni réseau
```

Le dry-run écrit dans `pipeline/out/` : médias placeholder, sous-titres `.vtt`,
`quality-report.json` et `catalog.preview.json`. Il ne touche jamais
`catalog/catalog.json`.

### APK debug

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`JAVA_HOME` est obligatoire : le JDK 26 du système fait échouer Gradle 8.9.
Le JDK 21 fourni avec Android Studio convient.

### APK release

```powershell
cd app
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

R8 + réduction des ressources : 6,1 Mo contre 26 Mo en debug. Signé avec
`app/dramaforge-release.jks`, dont les mots de passe sont dans
`app/local.properties`. Ces deux fichiers sont exclus de git (`*.jks`,
`local.properties`) et ne doivent jamais être commités : **le dépôt est public**.

Sauvegardez le keystore hors de la machine. Le perdre rend impossible toute
mise à jour d'une app déjà installée : Android refuse un APK signé par une
autre clé.

Prérequis locaux optionnels (FFmpeg, edge-tts) : voir
[scripts/README.md](scripts/README.md).

## État d'avancement

| Pièce | État |
|---|---|
| Contrat de catalogue (`catalog/`) | schéma JSON + miroir zod + validateur, 1 série d'exemple factice |
| Pipeline : prompts, schémas, `claude -p`, TTS, images, sous-titres, publication R2 | implémenté, dry-run vert, 37 tests |
| Pipeline : montage FFmpeg + HLS | implémenté, **non exécuté** (FFmpeg absent de la machine de dev) |
| Pipeline : `LocalSdProvider` (ComfyUI) | point d'intégration, graphe à renseigner |
| App : coque de navigation PERSONAL_MODE, DA, i18n FR/EN | ✅ compile — APK debug 26 Mo, APK release signé 6,1 Mo (non testé au runtime) |
| App : génération quotidienne de scripts (clé API dans les Paramètres) | ✅ compile, 27 tests unitaires, **non testée au runtime** |
| App : `StaticCatalogRepository` + cache Room | à faire — remplace `SampleCatalog` |
| App : lecteur vertical Media3 paginé | à faire |
| Wrapper Gradle (`app/gradlew`) | ✅ généré en 8.9 et commité |

## Workflows

| Workflow | Déclencheur | Rôle |
|---|---|---|
| `ci.yml` | push, PR | typecheck, tests, contrat du catalogue, dry-run |
| `weekly-drama.yml` | lundi 03h UTC, manuel | génération complète : **seul** endroit autorisé |
| `pages.yml` | modification de `catalog/` | publie le catalogue, jamais s'il est invalide |

Secrets GitHub attendus : `CLAUDE_CODE_OAUTH_TOKEN`, `R2_ACCOUNT_ID`,
`R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`. Rien en clair dans le
dépôt (il est public) ni dans l'APK.

## Garde-fous

- Fair-use : 1 série par semaine, 1 génération + 2 régénérations maximum par
  épisode (`MAX_ATTEMPTS_PER_EPISODE`). `.claude/settings.json` refuse la
  génération complète en local.
- `STYLE_LOCK` est immuable et l'`appearance` de chaque personnage est
  réinjectée mot pour mot dans les prompts d'image : c'est ce qui garde les
  personnages identiques d'un épisode à l'autre.
- Originalité : les titres passent par `excludedTerms` de `catalog.json`, et
  chaque épisode subit une revue de sûreté et de droits avant rendu. Les échecs
  sont consignés dans `quality-report.json` ; un `blocker` arrête la publication.

# DramaCam
