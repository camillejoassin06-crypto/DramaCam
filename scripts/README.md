# Prérequis locaux

Rien de tout ceci n'est nécessaire pour `npm run generate:dry` : le dry-run
utilise des fournisseurs placeholder et n'appelle ni le modèle, ni le réseau.
Ces outils servent à reproduire en local ce que fait `weekly-drama.yml`.

## FFmpeg (montage, HLS, mesure de durée)

```powershell
winget install Gyan.FFmpeg
# puis rouvrir le terminal et vérifier :
ffmpeg -version
ffprobe -version
```

Sans FFmpeg, le pipeline saute le montage et le consigne dans
`pipeline/out/quality-report.json` : en dry-run c'est un avertissement, en
génération complète c'est bloquant.

## edge-tts (voix françaises, gratuit)

Nécessite Python 3.

```powershell
winget install Python.Python.3.12
pip install edge-tts
edge-tts --list-voices | Select-String fr-FR
```

Si edge-tts est indisponible, le pipeline bascule sur `KokoroProvider` (instance
locale, `KOKORO_URL`), puis sur le placeholder en dernier recours.

## Images

- `IMAGE_PROVIDER=placeholder` (défaut) : PNG unis déterministes, aucun GPU.
- `IMAGE_PROVIDER=local-sd` : instance ComfyUI sur `COMFYUI_URL`. Le graphe de
  workflow reste à renseigner dans `pipeline/src/images/local-sd.ts`.
- `IMAGE_PROVIDER=ai-studio` : offre **gratuite** uniquement, clé dans
  `AI_STUDIO_API_KEY`. Ne jamais activer un plan facturé (cf. CLAUDE.md).

## Android

Rien à installer si Android Studio est présent : le wrapper Gradle 8.9 est
commité, et le SDK (`platforms/android-35`, `build-tools/35.0.0`) vient avec
Android Studio.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**`JAVA_HOME` n'est pas optionnel.** Gradle 8.9 ne supporte pas le JDK 26
installé sur cette machine : le build échoue avec le message laconique
« 26.0.1 ». Le JDK 21 livré avec Android Studio (`jbr`) fonctionne.

Pour éviter de redéfinir la variable à chaque terminal, on peut la fixer une
fois pour toutes les sessions :

```powershell
[Environment]::SetEnvironmentVariable(
  "JAVA_HOME", "C:\Program Files\Android\Android Studio\jbr", "User")
```

`app/local.properties` contient le chemin du SDK et les mots de passe de
signature. Il est propre à la machine et ignoré par git : à recréer sur un autre
poste.

### Build release

```powershell
cd app
./gradlew assembleRelease
```

La clé de signature `app/dramaforge-release.jks` (RSA 4096, PKCS12, valide
~27 ans) a été créée avec :

```powershell
keytool -genkeypair -v -keystore dramaforge-release.jks -alias dramaforge `
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12 `
  -dname "CN=DramaForge, OU=Personal, O=DramaForge, C=FR"
```

Si `RELEASE_STORE_FILE` est absent de `local.properties`, ou si le keystore
n'existe pas, `assembleRelease` produit quand même un APK — **non signé**, donc
non installable. C'est volontaire : mieux vaut un artefact explicitement non
signé qu'un échec de build incompréhensible sur un autre poste.

**Sauvegardez le keystore hors de cette machine.** Android refuse d'installer
une mise à jour signée par une autre clé : le perdre signifie désinstaller
l'app sur chaque appareil pour repartir d'une nouvelle clé.

Le dépôt étant public, ni `*.jks` ni `local.properties` ne doivent y entrer
(déjà dans `.gitignore` — ne jamais forcer avec `git add -f`).

Android Studio reste utile pour l'émulateur et le profilage mémoire.

## Secrets

Aucun secret ne vit dans le dépôt (il est public) ni dans l'APK. En local,
copier `.env.example` vers `.env` (ignoré par git) ; en CI, utiliser les GitHub
Secrets listés dans `.github/workflows/weekly-drama.yml`.

Le jeton d'abonnement Claude Code pour le mode headless se crée avec
`claude setup-token`, puis se range dans le secret `CLAUDE_CODE_OAUTH_TOKEN`.
