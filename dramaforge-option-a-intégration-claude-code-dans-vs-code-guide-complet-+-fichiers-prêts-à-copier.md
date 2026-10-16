# 🧠 DramaForge Option A — Intégration Claude Code dans Visual Studio Code

> Guide pas-à-pas pour piloter le projet « zéro serveur » (Option A) depuis Claude Code dans VS Code, avec les fichiers `CLAUDE.md` et `.claude/agents/*.md` **prêts à copier**, la séquence de briefs de démarrage et le workflow multi-agents.

---

## 1. 📥 Installation (10 minutes)

```text
1. Installer Visual Studio Code      -> https://code.visualstudio.com
2. Installer l'extension "Claude Code" (Anthropic) :
   VS Code → Extensions (Ctrl+Shift+X) → rechercher "Claude Code" → Installer
   (ou en terminal : npm install -g @anthropic-ai/claude-code, puis `claude`)
3. Lancer `claude` dans le terminal intégré de VS Code (Ctrl+ù) → suivre la
   connexion (login Anthropic / abonnement existant)
4. Créer et ouvrir le dossier projet : mkdir dramaforge && code dramaforge
5. Extensions VS Code recommandées (pour vous, l'humain — l'agent n'en a pas besoin) :
   - Kotlin Language (fwcd) · Gradle for Java · Error Lens · GitLens
6. Android Studio reste nécessaire SEULEMENT pour : émulateur, profilage mémoire,
   signature de l'APK. Tout le reste du code se pilote depuis VS Code + Claude Code.
```

**Vérification** : dans le terminal de VS Code, `claude` doit répondre. Taper `/doctor` pour valider l'installation, puis `/agents` — la liste doit afficher vos agents après l'étape 3.

---

## 2. 📁 Structure du projet (Option A)

```text
dramaforge/
├── CLAUDE.md                     # ← contrat projet (section 3, à copier tel quel)
├── .claude/
│   ├── settings.json             # permissions (voir 6.2)
│   └── agents/                   # ← agents spécialisés (section 4)
│       ├── android-developer.md
│       ├── ai-pipeline-engineer.md
│       ├── qa-engineer.md
│       └── product-designer.md
├── app/                          # App Android (Kotlin/Compose, PERSONAL_MODE)
├── pipeline/                     # Génération hebdo (TypeScript)
│   ├── src/
│   │   ├── prompts/ (concept.md, bible.md, episode.md, quality.md)
│   │   ├── schemas/ (zod)
│   │   ├── tts/ (EdgeTtsProvider, KokoroProvider)
│   │   ├── images/ (LocalSdProvider, AiStudioProvider)
│   │   ├── render.ts · subtitles.ts · hls.ts · publish.ts
│   │   └── generate-weekly.ts    # orchestrateur (appelé par claude -p)
├── catalog/
│   ├── schema.json               # contrat du catalogue (validé zod)
│   └── catalog.json              # LE catalogue servi par GitHub Pages
├── .github/workflows/
│   └── weekly-drama.yml          # cron hebdo (repo public = gratuit)
└── scripts/ (edge-tts install, r2 sync, bootstrap-apk)
```

---

## 3. 📄 `CLAUDE.md` — à copier à la racine du repo

```markdown
# CLAUDE.md — DramaForge (Option A : zéro serveur, 2-3 utilisateurs, full gratuit)

## Contexte produit
Plateforme personnelle de dramas verticaux courts (épisodes 2-3 min, swipe
vertical, style DramaWave mais 100 % original : marque, contenu et code).
Usage STRICTEMENT personnel : 2-3 appareils, APK sideloadé, PERSONAL_MODE
(tout est débloqué — pas de monétisation, pas de Play Store).

## Architecture (ne pas la contredire)
- PAS de backend : le catalogue est un fichier `catalog/catalog.json` servi par
  GitHub Pages ; les vidéos sont sur Cloudflare R2 (bucket public, free 10 Go).
- App Android Kotlin + Jetpack Compose + Media3, `BuildConfig.PERSONAL_MODE=true`.
  Source de données unique : `StaticCatalogRepository` (parse catalog.json, cache Room).
  Progression/favoris : 100 % local (Room), aucune API.
- Pipeline TypeScript (Node 22) : génération HEBDOMADAIRE d'une série de
  50 épisodes via `claude -p` (headless, abonnement — PAS l'API Anthropic payante),
  TTS edge-tts/Kokoro, images SD local ou AI Studio, montage FFmpeg, HLS,
  upload R2, mise à jour de catalog.json + commit.

## Règles absolues
- AUCUN coût récurrent : interdiction d'ajouter un service payant, une clé API
  facturée à l'usage, ou l'API Anthropic directe. La génération passe par
  `claude -p` dans le workflow GitHub Actions uniquement.
- PERSONAL_MODE : jamais réactiver wallet/billing/parrainage sans demande explicite.
- Contenu original obligatoire : ne jamais réutiliser nom, logo, texte ou visuel
  de DramaWave ou de toute marque. Les titres générés passent par la liste
  d'exclusion de catalog.json.
- Aucune clé/secret en clair dans le repo (public !) : GitHub secrets uniquement.
- Kotlin : Compose + Hilt + Media3, pas de XML layouts. `./gradlew assembleDebug`
  doit passer avant tout commit feature.
- Pipeline : sorties JSON strictes validées par zod (schemas/), STYLE_LOCK
  immuable pour la cohérence des personnages.
- Commits conventional : feat(app): ... | feat(pipeline): ... | fix(app): ... |
  chore(ci): ...

## Commandes
- App debug :      cd app && ./gradlew assembleDebug
- App install :    adb install -r app/app/build/outputs/apk/debug/app-debug.apk
- Lint app :       cd app && ./gradlew lint
- Catalogue local : npx tsx pipeline/src/validate-catalog.ts   (zod, 1 s)
- Pipeline dry-run : npx tsx pipeline/src/generate-weekly.ts --dry-run --episodes 3
- Pipeline complet : déclencher le workflow GitHub (weekly-drama.yml) — ne JAMAIS
  lancer la génération complète en local sans accord explicite (fair-use).

## Points d'attention
- R2 free = 10 Go (~10-12 séries 480p) : pipeline/publish.ts doit vérifier la
  taille du bucket et purge la plus ancienne série si > 9 Go.
- Fair-use Claude Code : 1 série/semaine max via `claude -p`. Les boucles de
  régénération sont limitées à 2 tentatives par épisode.
- catalog.json est l'unique contrat app↔contenu : toute modification du schéma
  met à jour catalog/schema.json + StaticCatalogRepository dans le même commit.
```

---

## 4. 🤖 Agents — `.claude/agents/` (à copier)

**`.claude/agents/android-developer.md`**

```markdown
---
name: android-developer
description: Développe tous les écrans, la navigation et le lecteur vertical de l'app Android en mode PERSONAL_MODE (catalogue statique, pas d'API). À utiliser pour toute tâche dans app/.
tools: Read, Edit, Write, Bash, Grep, Glob
model: inherit
---
Tu es le développeur Android senior de DramaForge (Option A).
- Stack : Jetpack Compose Material3, Hilt, Media3 ExoPlayer, Room, Coil, Paging 3.
- PERSONAL_MODE : la seule source de données est StaticCatalogRepository
  (catalog.json + cache Room). Aucun appel réseau sauf les médias R2.
  Épisodes toujours EpisodeState.UNLOCKED ; Wallet/Referral/VIP inexistants.
- Écrans attendus : Splash, Home (Tendance/Classement/Inédit/Catégories ▾),
  Détail + grille épisodes, Lecteur vertical paginé (pool max 3 ExoPlayer,
  cache LRU 512 Mo, reprise de progression locale), Bibliothèque (historique,
  favoris, offline), Paramètres (langue FR/EN à chaud, sous-titres, qualité).
- DA : fond sombre #0E0B16, accent violet #6C2BD9, or #FFD54F, badges
  EXCLUSIVE/NOUVEAU, cartes 2x2 arrondies.
- Après chaque tâche : `./gradlew assembleDebug` doit passer ; écrire les tests
  unitaires des ViewModels modifiés.
```

**`.claude/agents/ai-pipeline-engineer.md`**

```markdown
---
name: ai-pipeline-engineer
description: Développe et exécute le pipeline de génération hebdomadaire (prompts, claude -p headless, edge-tts, SD local, FFmpeg, HLS, R2, catalog.json). À utiliser pour toute tâche dans pipeline/.
tools: Read, Edit, Write, Bash, Grep, Glob
model: inherit
---
Tu es l'ingénieur du pipeline IA de DramaForge (Option A, gratuit).
- Objectif : 1 série de 50 épisodes de 2-3 min par SEMAINE, coût 0 €.
- La génération de texte passe par `claude -p "<prompt>" --output-format json`
  (abonnement) — JAMAIS l'API Anthropic directe, jamais plus de 2 régénérations
  par épisode (fair-use).
- TTS : EdgeTtsProvider (edge-tts, voix fr-FR-*) avec fallback KokoroProvider.
  Interface TtsProvider immuable (Sprint 2).
- Images : 8 plans/épisode (pas 12), STYLE_LOCK + "appearance" mot pour mot
  (cohérence), LocalSdProvider (ComfyUI) ou AiStudioProvider si pas de GPU.
- Toute sortie modèle est validée zod AVANT usage ; en cas d'échec : journaliser
  dans pipeline/out/quality-report.json et continuer.
- Publication : upload R2 -> purge si bucket > 9 Go -> régénérer catalog.json ->
  valider avec validate-catalog.ts -> commit "feat(catalog): série <titre>".
- FFmpeg : vertical 1080x1920, Ken Burns, musique duckée, HLS 480p (720p si
  place suffisante), .vtt des sous-titres.
```

**`.claude/agents/qa-engineer.md`**

```markdown
---
name: qa-engineer
description: Relit le code, exécute les tests, vérifie assembleDebug, le pipeline en dry-run et la qualité des épisodes générés (cohérence personnages, sous-titres, contenu original). À utiliser avant chaque commit majeur et après chaque génération.
tools: Read, Edit, Bash, Grep, Glob
model: inherit
---
Tu es le QA lead de DramaForge (Option A).
Checklist avant de valider une PR :
- cd app && ./gradlew assembleDebug lint testDebugUnitTest
- npx tsx pipeline/src/validate-catalog.ts
- npx tsc --noEmit (pipeline)
- 0 chaîne FR/EN codée en dur dans feature/ (resources strings.xml obligatoires)
- PersonnalMode activé : aucune référence wallet/billing dans la nav
Après une génération : vérifier 2 épisodes au hasard (voix cohérentes par
personnage, sous-titres alignés, aucun contenu de marque concurrente, durée
2:00-3:15). Bloquer la publication si un critère échoue.
```

**`.claude/agents/product-designer.md`**

```markdown
---
name: product-designer
description: Conçoit l'UX/UI, la DA, les parcours et tous les textes FR/EN (strings.xml) de l'app. À utiliser pour toute question design ou wording.
tools: Read, Edit, Write, Grep, Glob
model: inherit
---
Tu es le designer produit de DramaForge.
- DA : sombre premium, violet/or, cartes 2x2, badges, transitions douces
  (300 ms), lecteur plein écran minimal (overlay titre + épisode).
- Parcours personnel : pas de paywall — le hook est le classement INÉDIT et
  la reprise de lecture. L'écran Home doit afficher la nouvelle série de la
  semaine en 1er (badge NOUVEAU).
- Tous les libellés en resources strings.xml (EN défaut + values-fr), jamais
  en dur. Titres de séries : phrase accrocheuse 1re personne, original.
```

---

## 5. 🚀 Séquence de briefs de démarrage (copier-coller dans Claude Code)

**Brief 0 — bootstrap (une seule fois) :**

```text
Crée l'ossature du projet Option A décrite dans CLAUDE.md : dossiers app/
(Android Kotlin/Compose avec Gradle + PERSONAL_MODE), pipeline/ (TypeScript +
zod + tsconfig), catalog/ (schema.json + catalog.json vide avec 1 série
d'exemple factice pointant vers une URL R2 placeholder), .github/workflows/
weekly-drama.yml (cron lundi 03h UTC, étapes : claude -p génération ->
edge-tts -> FFmpeg -> upload R2 -> commit catalog). Aucun secret en clair,
placeholders ${{ secrets.* }}. Utilise les agents si besoin, puis commit
"chore: bootstrap option A".
```

**Brief 1 — app Android (agent android-developer) :**

```text
Utilise l'agent android-developer : implémente StaticCatalogRepository
(parse catalog.json, cache Room 7 jours), les modèles Drama/Episode, puis
l'écran Home complet (4 onglets, grille 2x2, badges, i18n FR/EN via
strings.xml) avec des données du catalogue d'exemple. assembleDebug doit
passer et l'app doit afficher la grille à l'émulateur.
```

**Brief 2 — lecteur vertical :**

```text
Utilise l'agent android-developer : lecteur vertical paginé (VerticalPager,
pool 3 ExoPlayer, cache LRU 512 Mo, reprise Room, sous-titres .vtt, offline
si HLS local). Test swipe 50 épisodes sans OOM. assembleDebug + tests.
```

**Brief 3 — pipeline (agent ai-pipeline-engineer) :**

```text
Utilise l'agent ai-pipeline-engineer : crée prompts/ (concept, bible 50 beats,
épisode 8 plans, qualité — en adaptant les prompts du blueprint avec les
contraintes 0 €), schemas/ zod, EdgeTtsProvider (CLI edge-tts), la boucle
claude -p headless avec 2 régénérations max, le rendu FFmpeg vertical + HLS
480p, et publish.ts (R2 + purge 9 Go + régénération catalog.json +
validate-catalog.ts). Termine par un dry-run de 3 épisodes avec des images
placeholder et vérifie catalog.json.
```

**Brief 4 — première vraie série :**

```text
Déclenche manuellement le workflow weekly-drama.yml (workflow_dispatch) et
surveille. S'il n'est pas encore configuré (secrets R2 manquants), exécute
uniquement la partie concept+bible en local via claude -p (2 appels) et
présente-moi la bible pour validation avant d'aller plus loin.
```

---

## 6. 💡 Workflow multi-agents au quotidien

### 6.1 Délégation dans une session unique
Claude Code (session principale) choisit tout seul le bon agent grâce au
frontmatter `description` — vous pouvez écrire naturellement :

```text
"l'écran Détail a un bug quand je tourne le téléphone en paysage"
→ délègue à android-developer automatiquement
"les sous-titres de l'épisode 12 sont décalés d'une seconde"
→ délègue à ai-pipeline-engineer (ou qa-engineer pour le diagnostic)
```

Pour forcer un agent : `"utilise l'agent qa-engineer pour ..."`.

### 6.2 `.claude/settings.json` (permissions recommandées)

```json
{
  "permissions": {
    "allow": [
      "Bash(./gradlew *)", "Bash(npx tsx *)", "Bash(npx tsc *)",
      "Bash(git add *)", "Bash(git commit *)", "Bash(adb *)",
      "Bash(edge-tts *)", "Bash(ffmpeg *)"
    ],
    "deny": [
      "Bash(npx tsx pipeline/src/generate-weekly.ts)",
      "Bash(rm -rf *)"
    ]
  }
}
```

> Le `deny` sur `generate-weekly.ts` protège le fair-use : la génération
> complète ne se lance QUE depuis GitHub Actions (ou après votre accord).

### 6.3 Parallélisation (recommandé dès que le projet avance)

```text
Terminal 1 : claude   → "Continue l'app : écran Bibliothèque + offline"
Terminal 2 : claude   → "Travaille sur le pipeline : AiStudioProvider"
Les deux sessions travaillent sur des dossiers disjoints (app/ vs pipeline/)
— aucun conflit. Règle : 1 domaine par session, commit par session.
```

### 6.4 Cycle hebdomadaire type

```text
Lundi   : le workflow GitHub tourne seul (génération + publication)
Mardi   : "qa-engineer : revue de la série publiée" (2 épisodes au hasard)
Mercredi: si retours → "corrige et régénère les épisodes 12 et 31"
Jeudi   : 1 brief produit/design max (nouvelle fonctionnalité app)
Vendredi: aucun run IA — veille budget/fair-use
```

### 6.5 Commandes utiles de Claude Code à connaître

```text
/agents        → voir/éditer les agents chargés
/init          → régénère CLAUDE.md si absent
/doctor        → diagnostic installation
/clear         → repartir d'une session vierge (contexte propre)
/compact       → compacter le contexte quand la conversation s'allonge
Esc × 2        → revenir en arrière dans la conversation
claude -p "…"  → mode headless (celui qu'utilise le workflow hebdo)
```

---

## 7. ✅ Checklist de validation de l'intégration

- [ ] `claude` répond dans le terminal VS Code ; `/doctor` OK
- [ ] Repo `dramaforge` public créé ; `CLAUDE.md` (section 3) à la racine
- [ ] `.claude/agents/` contient les 4 agents ; `/agents` les liste
- [ ] `.claude/settings.json` appliqué (allow/deny de la section 6.2)
- [ ] Secrets GitHub créés : `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`
- [ ] Brief 0 exécuté : `./gradlew assembleDebug` et `npx tsc --noEmit` verts
- [ ] Brief 1-3 exécutés : app qui affiche le catalogue factice sur l'émulateur
- [ ] Premier workflow_dispatch du `weekly-drama.yml` : 3 épisodes dry-run OK
- [ ] APK installé sur 1 téléphone réel (2-2 restants quand prêt 🍿)