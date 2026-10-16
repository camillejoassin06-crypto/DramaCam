# 🚀 DramaForge — Sprint 4 : Industrialisation & lancement

> Périmètre : QA de bout en bout (qa-engineer) · lecteur optimisé réseau/mémoire (android-developer) · Profil + Paramètres complets (android-developer) · i18n FR/EN (android-developer) · beta fermée + Crashlytics + release Play Store (devops-engineer).

**Dispatch dans Claude Code :**

```text
1. android-developer   -> core/network-adaptive + player prefetch (section 2)
2. android-developer   -> feature/profile + settings + i18n (sections 3-4)
3. qa-engineer         -> matrice QA + tests e2e UI + crash-free (section 1)
4. devops-engineer     -> crashlytics + beta + release.yml + Play Console (section 5)
5. Tous               -> revue finale + merge main + tag v1.0.0-beta.1
```

---

## 1. ✅ qa-engineer — QA de bout en bout

### 1.1 Matrice de test manuelle/automatique (bloquante pour la release)

| # | Scénario | Attendu | Type |
|---|---|---|---|
| 1 | Swipe rapide 50 épisodes d'affilée | 0 crash, < 250 Mo RAM, GC sans fuite (LeakCanary) | Auto (Macrobenchmark) + manuel |
| 2 | Double-tap sur "Débloquer" (réseau lent) | 1 seul débit, 2e réponse idempotente | Auto (e2e backend déjà vert) + UI test |
| 3 | Achat sandbox d'un pack pendant appel entrant | Validation serveur OK, pièces créditées 1× | Manuel (licence Google Play) |
| 4 | Mode avion → épisode téléchargé | Lecture immédiate, progression sauvegardée localement puis sync | Auto (UI test) |
| 5 | Kill de l'app pendant le download ép. 12 | WorkManager reprend (RUNNING), fichier non corrompu | Auto |
| 6 | Expiration du token stream (> 5 min en pause) | Re-fetch automatique de l'URL signée, pas d'erreur utilisateur | Auto |
| 7 | Bonus quotidien ×2 le même jour | `ALREADY_CLAIMED`, pas de double crédit | Auto (e2e) |
| 8 | 3 séries générées successives | Voix identiques par personnage, cohérence visuelle, 0 doublon d'épisode en DB | Auto (pipeline) |
| 9 | VIP expiré depuis 24 h | `resyncSubscriptions` → épisodes re-verrouillés, wallet correct | Auto |
| 10 | Deep link push sur une série non publiée | Erreur 404 gérée proprement (écran "contenu indisponible") | Manuel |
| 11 | Changement de langue FR→EN en cours de session | Tous les écrans localisés (aucune chaîne codée en dur) | Auto (lint du repo) |
| 12 | Réception push + cold start | Tap notification → Détail, pas de splash bloquant | Manuel |

### 1.2 Tests UI automatisés (Compose UI Test) — parcours critiques

```kotlin
// app/src/androidTest/.../UnlockFlowTest.kt
class UnlockFlowTest {

    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    @get:Rule val mockWebServer = MockWebServerRule()   // backend mocké

    @Test
    fun `affordable episode unlock debits coins and plays`() {
        composeRule.onNodeWithText("Inédit").performClick()
        composeRule.onAllNodes(hasTestTag("cover_card"))[0].performClick()
        composeRule.onNodeWithText("🪙 500").performClick()   // EpisodeState.AFFORDABLE
        composeRule.onNodeWithText("Débloquer · 🪙 500").assertExists()
        composeRule.onNodeWithText("Débloquer · 🪙 500").performClick()
        composeRule.waitUntil { composeRule.onAllNodes(hasTestTag("player_surface"))
            .fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `locked episode with low wallet redirects to store`() {
        composeRule.onNodeWithText("Tendance").performClick()
        composeRule.onAllNodes(hasTestTag("cover_card"))[1].performClick()
        composeRule.onNodeWithText("Recharger des pièces").performClick()
        composeRule.onNodeWithText("Mon solde").assertIsDisplayed()  // WalletScreen
    }
}

// app/src/androidTest/.../PlayerStressTest.kt
@Test
fun `swipe_50_episodes_no_crash`() {
    launchPlayerWith(dramaId = "stress_50", startEpisode = 1)
    repeat(50) { device.swipe(device.displayWidth / 2, device.displayHeight / 4,
                              device.displayWidth / 2, device.displayHeight * 3 / 4, 50) }
    device.waitForIdle()
    assertThat(processMemory()).isLessThan(250_000_000)  // < 250 Mo
    assertThat(LeakCanary.hadLeaks()).isFalse()
}
```

### 1.3 Seuils de release (Go/No-Go)

- **Crash-free sessions ≥ 99,5 %** (Crashlytics, mesuré sur la beta)
- 12/12 scénarios matrice verts · tests unitaires + e2e backend verts · `assembleRelease` signé reproductible
- 0 fuite mémoire confirmée par LeakCanary sur le parcours lecteur
- ANR < 0,47 % (seuil Play Store)

---

## 2. 📱 android-developer — Lecteur optimisé : réseau adaptatif + cache

### 2.1 `core/player/NetworkAwareQualityManager.kt`

```kotlin
package com.dramaforge.app.core.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class QualityPolicy { AUTO, WIFI_1080, CELLULAR_480, DATA_SAVER_480 }

data class NetworkProfile(
    val unmetered: Boolean,
    val qualityPolicy: QualityPolicy,
)

@Singleton
class NetworkAwareQualityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,   // préférence utilisateur (Auto / Éco données)
) {
    val userPolicy: StateFlow<QualityPolicy> = settings.qualityPolicy

    /** Profil réseau live : Wi-Fi → 1080p, cellulaire → 480p + préchargement réduit. */
    fun currentProfile(): NetworkProfile {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false
        val policy = when {
            userPolicy.value == QualityPolicy.DATA_SAVER_480 -> QualityPolicy.DATA_SAVER_480
            unmetered -> QualityPolicy.WIFI_1080
            else -> QualityPolicy.CELLULAR_480
        }
        return NetworkProfile(unmetered, policy)
    }
}
```

### 2.2 Cache média partagé + préchargement adaptatif (patch `VerticalPlayerManager`)

```kotlin
// core/player/PlayerCache.kt — cache disque partagé entre tous les players
@Singleton
class PlayerCache @Inject constructor(@ApplicationContext context: Context) {
    // Cache segments HLS LRU 512 Mo : les épisodes regardés resservent depuis le disque.
    val simpleCache = SimpleCache(
        File(context.cacheDir, "media"),
        LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
        StandaloneDatabaseProvider(context),
    )

    fun dataSourceFactory(httpFactory: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setCacheWriteDataSinkFactory(null)      // lecture en cache, écriture upstream standard
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)  // jamais bloquer si cache corrompu
}

// feature/player/PlayerViewModel.kt (patch)
private fun createPlayer(episodeId: String, source: String): ExoPlayer {
    val profile = networkQuality.currentProfile()
    return ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(playerCache.dataSourceFactory(CronetDataSource.Factory(app))))
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Cellulaire : petits buffers (économie données + RAM),
                // Wi-Fi : buffer confortable pour un swipe sans rebuffering.
                .setBufferDurationsMs(
                    if (profile.unmetered) 15_000 else 5_000,   // min
                    if (profile.unmetered) 50_000 else 15_000,   // max
                    1_000, 2_000)
                .build())
        .build().apply {
            val (maxW, maxH) = when (profile.qualityPolicy) {
                QualityPolicy.WIFI_1080 -> 1080 to 1920
                else -> 480 to 854      // CELLULAR_480 & DATA_SAVER_480 → rendition 480p
            }
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setMaxVideoSize(maxW, maxH)
                .build()
            // ...
        }
}

/** Préchargement du clip du prochain épisode : on prépare le player n+1
 *  MAIS on ne démarre la lecture qu'à l'affichage (économie batterie). */
fun prefetchNextPlayer(currentIndex: Int) {
    val next = episodes.getOrNull(currentIndex + 1) ?: return
    if (!next.isLocked) {
        prefetch(next.id)
        players.getOrPut(next.id) { createPlayer(next.id, sourceOf(next.id)) }
            .apply { playWhenReady = false; prepare() }   // buffer prêt, lecture suspendue
    }
}
```

**Optimisations incluses :** cache LRU 512 Mo (re-visionnage instantané) · cap qualité par réseau (480p cellulaire) · buffers adaptatifs · préchargement du player n+1 **sans lecture** · re-fetch du token signé expiré (scénario 6 de la matrice) :

```kotlin
// playerFor() : gérer l'expiration du lien signé (TTL 5 min)
if (isTokenExpired(episodeId)) {          // exp du cache dépassé
    viewModelScope.launch {
        prefetch(episodeId, force = true) // GET /stream/:id/url à nouveau
        // le SimpleCache sert les segments déjà en cache : pas de re-téléchargement
    }
}
```

**DoD lecteur** : RAM < 250 Mo sur swipe 50 épisodes · rebuffering < 1 % en Wi-Fi · 480p automatique en 4G · re-fetch token invisible pour l'utilisateur.

---

## 3. 📱 android-developer — Profil + Paramètres complets

### 3.1 `feature/profile/ProfileScreen.kt`

```kotlin
@Composable
fun ProfileScreen(
    onGoWallet: () -> Unit, onGoVip: () -> Unit, onGoReferral: () -> Unit,
    onGoSettings: () -> Unit, onLogout: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user = state.user

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // --- Carte utilisateur + statut VIP ---
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = user.avatarUrl, contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(user.displayName, fontWeight = FontWeight.Bold)
                    Text(if (user.vipActive) "👑 VIP actif" else "Membre gratuit",
                        color = if (user.vipActive) Color(0xFFB39DFF) else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- Raccourcis économiques ---
        SectionTitle(stringResource(R.string.profile_section_account))
        SettingRow(icon = "🪙", label = stringResource(R.string.profile_wallet),
            value = stringResource(R.string.profile_coins, user.coins + user.bonusCoins),
            onClick = onGoWallet)
        if (!user.vipActive)
            SettingRow(icon = "👑", label = stringResource(R.string.profile_vip), onClick = onGoVip)
        SettingRow(icon = "🎁", label = stringResource(R.string.profile_referral), onClick = onGoReferral)
        SettingRow(icon = "⚙️", label = stringResource(R.string.profile_settings), onClick = onGoSettings)

        // --- Légal (obligatoire Play Store / RGPD) ---
        SectionTitle(stringResource(R.string.profile_section_legal))
        SettingRow(label = stringResource(R.string.profile_privacy),
            onClick = { openUrl("https://dramaforge.app/privacy") })
        SettingRow(label = stringResource(R.string.profile_terms),
            onClick = { openUrl("https://dramaforge.app/terms") })

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.profile_logout))
        }
        TextButton(onClick = { viewModel.confirmDeleteAccount() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(stringResource(R.string.profile_delete_account))
        }
        // Suppression de compte : confirmation + backend anonymise l'utilisateur
        // et purge les tokens FCM (conformité RGPD — art. 17 : droit à l'effacement).
    }
}
```

### 3.2 `feature/settings/SettingsScreen.kt` (paramètres techniques)

```kotlin
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

        SectionTitle(stringResource(R.string.settings_section_language))
        LanguageSelector(current = settings.appLanguage,
            onSelected = { viewModel.setAppLanguage(it) })   // cf. i18n, section 4

        SectionTitle(stringResource(R.string.settings_section_subtitles))
        SwitchRow(label = stringResource(R.string.settings_subtitles_default),
            checked = settings.subtitlesEnabled,
            onChecked = { viewModel.setSubtitlesEnabled(it) })
        StepperRow(label = stringResource(R.string.settings_subtitles_size),
            value = settings.subtitleScale, range = 0.8f..1.6f, step = 0.1f,
            onChange = { viewModel.setSubtitleScale(it) })

        SectionTitle(stringResource(R.string.settings_section_video))
        RadioGroup(options = listOf(
            stringResource(R.string.settings_quality_auto),
            stringResource(R.string.settings_quality_480),
            stringResource(R.string.settings_quality_data_saver)),
            selected = settings.qualityPolicy, onSelect = { viewModel.setQualityPolicy(it) })

        SectionTitle(stringResource(R.string.settings_section_notifications))
        SwitchRow(stringResource(R.string.settings_notif_new_dramas),
            checked = settings.notifyNewDramas,
            onChecked = { viewModel.setNotifyNewDramas(it) })   // (un)subscribe topic FCM
        SwitchRow(stringResource(R.string.settings_notif_marketing),
            checked = settings.notifyMarketing, onChecked = { viewModel.setNotifyMarketing(it) })

        SectionTitle(stringResource(R.string.settings_section_storage))
        SettingRow(label = stringResource(R.string.settings_cache_media),
            value = formatMb(settings.mediaCacheMb), onClick = { viewModel.clearMediaCache() })
        SettingRow(label = stringResource(R.string.settings_downloads),
            value = formatMb(settings.downloadsMb), onClick = { /* nav library/downloads */ })
        // Les téléchargements expirent après 30 jours (licence VIP) : affiché ici.
    }
}
```

**DoD profil/paramètres** : changement de langue appliqué à chaud (recomposition sans restart) · sous-titres on/off + taille persistés (DataStore) et appliqués au lecteur · qualité vidéo → `NetworkAwareQualityManager` · suppression de compte = anonymisation RGPD côté backend · 0 chaîne codée en dur.

---

## 4. 🌍 android-developer — i18n FR/EN

### 4.1 Nettoyage : les chaînes codées des sprints 1-3 migrent en ressources

```text
res/
├── values/strings.xml            # ANGLAIS = défaut (langue Play Store de référence)
├── values-fr/strings.xml         # FR
├── locales_config.xml            # Android 13+ : langues par app
```

```xml
<!-- res/values/strings.xml (défaut EN) -->
<resources>
    <string name="home_tab_trending">Trending</string>
    <string name="home_tab_ranking">Ranking</string>
    <string name="home_tab_new">New</string>
    <string name="home_tab_categories">Categories</string>
    <string name="badge_exclusive">EXCLUSIVE</string>
    <string name="badge_new">NEW</string>
    <string name="episode_free">FREE</string>
    <string name="locked_title">Episode %d locked</string>
    <string name="locked_unlock_cta">Unlock · 🪙 %d</string>
    <string name="locked_recharge">Buy coins</string>
    <string name="vip_cta_all">Go VIP · Unlock everything</string>
    <string name="detail_resume">Resume ep. %d</string>
    <string name="detail_play_first">Play episode 1</string>
    <string name="detail_unlock_all">Unlock all · 🪙 %d</string>
    <string name="wallet_daily_bonus">Daily bonus</string>
    <string name="wallet_daily_bonus_claim">Claim</string>
    <string name="wallet_daily_bonus_claimed">Claimed ✓</string>
    <string name="profile_logout">Log out</string>
    <string name="profile_delete_account">Delete my account</string>
</resources>
```

```xml
<!-- res/values-fr/strings.xml -->
<resources>
    <string name="home_tab_trending">Tendance</string>
    <string name="home_tab_ranking">Classement</string>
    <string name="home_tab_new">Inédit</string>
    <string name="home_tab_categories">Catégories</string>
    <string name="badge_exclusive">EXCLUSIVE</string>
    <string name="badge_new">NOUVEAU</string>
    <string name="episode_free">GRATUIT</string>
    <string name="locked_title">Épisode %d verrouillé</string>
    <string name="locked_unlock_cta">Débloquer · 🪙 %d</string>
    <string name="locked_recharge">Recharger des pièces</string>
    <string name="vip_cta_all">Passer VIP · Tout débloquer</string>
    <string name="detail_resume">Reprendre ép. %d</string>
    <string name="detail_play_first">Lire épisode 1</string>
    <string name="detail_unlock_all">Tout débloquer · 🪙 %d</string>
    <string name="wallet_daily_bonus">Bonus quotidien</string>
    <string name="wallet_daily_bonus_claim">Récupérer</string>
    <string name="wallet_daily_bonus_claimed">Récupéré ✓</string>
    <string name="profile_logout">Se déconnecter</string>
    <string name="profile_delete_account">Supprimer mon compte</string>
</resources>
```

```xml
<!-- res/locales_config.xml -->
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en"/>
    <locale android:name="fr"/>
</locale-config>
```

### 4.2 Changement de langue à chaud (AndroidX per-app language)

```kotlin
// feature/settings/LanguageSelector.kt
@Composable
fun LanguageSelector(current: AppLanguage, onSelected: (AppLanguage) -> Unit) {
    Column {
        listOf(AppLanguage.SYSTEM, AppLanguage.EN, AppLanguage.FR).forEach { lang ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(selected = current == lang) {
                    onSelected(lang)
                }.padding(vertical = 8.dp)) {
                RadioButton(selected = current == lang, onClick = null)
                Text(stringResource(lang.labelRes))
            }
        }
    }
}

// SettingsViewModel — applique la langue sans redémarrer l'app :
fun setAppLanguage(lang: AppLanguage) = viewModelScope.launch {
    settingsRepository.setAppLanguage(lang)
    val tag = when (lang) {
        AppLanguage.SYSTEM -> null          // suit la langue système
        AppLanguage.EN -> "en"
        AppLanguage.FR -> "fr"
    }
    // API per-app language (Android 13+) ; AppCompat backport jusqu'à API 17.
    AppCompatDelegate.setApplicationLocales(tag?.let { LocaleListCompat.forLanguageTags(it) }
        ?: LocaleListCompat.getEmptyLocaleList())
    // Topic FCM aligné : new_dramas_fr -> new_dramas_en si switch de langue
    pushSubscription.switchLocaleTopic(tag ?: currentDeviceLanguage())
}
```

**Règle de lint (à ajouter en CI) :** `./gradlew lint` + grep CI interdisant les littéraux FR/EN dans les `Text("...")` des `feature/` (seules les ressources `strings.xml` autorisées) — exécution : `rg -n 'Text\("' app/src/main --glob '*.kt'` en job CI avec liste d'exceptions.

---

## 5. 🚀 devops-engineer — Beta fermée + Crashlytics + release Play Store

### 5.1 Firebase : Crashlytics + Analytics (gradle)

```kotlin
// app/build.gradle.kts (ajouts)
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
}
// app module :
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
}
// google-services.json : dev (debug) + prod (release) via flavors ou dossiers source sets.
```

**Événements Analytics suivis (funnel monétisation) :**

```kotlin
object DramaForgeEvents {
    fun dramaOpened(id: String) = log("drama_opened", map("drama_id" to id))
    fun episodeWatched(n: Int, sec: Int) = log("episode_watched", map("ep" to n, "seconds" to sec))
    fun unlockOffered(coins: Int) = log("unlock_offered", map("price" to coins))
    fun unlockCompleted(coins: Int, ok: Boolean) = log("unlock_completed", map("price" to coins, "ok" to ok))
    fun storeOpened(source: String) = log("store_opened", map("source" to source))
    fun purchaseLaunched(productId: String) = log("purchase_launched", map("product" to productId))
    fun vipSubscribed() = log("vip_subscribed")
    fun pushOpened() = log("push_opened")
}
// KPIs pilotés : conversion unlock_offered -> unlock_completed (cible > 12 %),
// store_opened -> purchase_launched (cible > 25 %), ARPU par cohorte.
```

**Crash de test (build debug uniquement) :** bouton caché "Force crash" (`throw RuntimeException("Test Crashlytics")` derrière 7 taps sur la version) pour valider la remontée dans la console Firebase avant la beta.

### 5.2 Release signing & upload — `release.yml` (remplace le job release du Sprint 1)

```yaml
  release-play:
    if: startsWith(github.ref, 'refs/tags/v')
    needs: [backend, android]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - name: Decode keystore
        run: echo "${{ secrets.ANDROID_KEYSTORE_BASE64 }}" | base64 -d > app/dramaforge.keystore

      - name: Build signed AAB
        working-directory: app
        env:
          SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
          SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
          SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}
        run: ./gradlew bundleRelease

      - name: Upload vers Play Console (piste fermée)
        uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJsonPlainText: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
          packageName: com.dramaforge.app
          releaseFiles: app/app/build/outputs/bundle/release/app-release.aab
          track: beta                     # internal -> beta -> production
          inAppUpdatePriority: 2
          whatsNewDirectory: whatsnew/      # notes de version FR/EN
```

**Note importante :** activer **Play App Signing** (clé de signature gérée par Google) — la clé locale ne sert qu'au premier upload. Ajouter aussi `whatsnew/fr-FR.txt` et `whatsnew/en-US.txt`.

### 5.3 Checklist Play Console (devops + product-designer)

| Rubrique | Détail | Statut |
|---|---|---|
| **Fiche Store** | Titre 30 c. (« DramaForge — Dramas courts FR »), description courte 80 c., description complète avec mots-clés (dramas verticaux, romance, mafia, revanche), 4-8 captures (Tendance, Lecteur, Wallet, VIP) en 9:16 + feature graphic, vidéo de bande-annonce 30 s | À produire (image_generation) |
| **Évaluation du contenu** | Questionnaire IARC : contenu 16+, avertissement « contenu généré par IA » si exigé, pas de jeu d'argent (le parrainage ≠ argent réel), violence modérée des dramas mafia | À compléter |
| **Data Safety** | Collecté : email/téléphone (auth), identifiants d'achat, jetons device (push), historique de visionnage · Chiffré en transit · Droit de suppression (écran Profil) · Pas de partage tiers hors prestataires (Anthropic, R2, ElevenLabs : traiter en sous-traitants) | À déclarer |
| **Politique de confidentialité** | `https://dramaforge.app/privacy` — mentionner explicitement la génération IA du contenu et les sous-traitants | À publier |
| **Déclaration "contenu IA"** | Selon la politique Play en vigueur au moment de la soumission : signaler le contenu généré/assisté par IA | À vérifier avant soumission |
| **Paiements** | Produits créés dans Play Console : coins_500/1200/3000/8000 (INAPP), vip_monthly (BASE) ; ID strictement identiques à `COIN_PACKS` backend | À créer |
| **Comptes de test licence** | Ajouter les Gmail des testeurs pour les achats sandbox gratuits | À ajouter |
| **Cible API / politiques** | `targetSdk 35` conforme aux exigences Play de 2026 ; batterie/background : WorkManager conforme (pas de foreground abuse) | Conforme (build Sprint 1) |

### 5.4 Déploiement progressif (staged rollout)

```text
v1.0.0-beta.1  -> piste INTERNE       (équipe + 5 testeurs, Crashlytics validé)
v1.0.0-beta.2  -> piste FERMÉE         (20-100 testeurs recrutés, 1 semaine,
                                        critère : crash-free ≥ 99,5 %)
v1.0.0         -> PRODUCTION 10 %      (24 h : crash-free ≥ 99,7 % sinon HALT)
                -> 50 % (48 h) -> 100 % (monitoring Crashlytics + ANR + reviews 72 h)
```

**Post-release (pilotage) :** dashboard hebdo Crashlytics + Analytics (funnel, ARPU, coût IA/série ~30-70 € vs revenus) ; règle d'arrêt : si coût IA > revenus 7 jours consécutifs → passer le cron à 3 séries/semaine (variable `SERIES_PER_WEEK` dans le workflow, déclenché `if: [ $(( $(date +%u) % 2 )) -eq 1 ]` par ex.).

---

## 6. ✅ Definition of Done globale du Sprint 4 (et du projet)

| Critère | Agent |
|---|---|
| Matrice QA 12/12 verte, seuils crash-free ≥ 99,5 % vérifiés sur beta | qa-engineer |
| Lecteur : RAM < 250 Mo, 480p cellulaire, cache LRU 512 Mo, re-fetch token invisible | android-developer |
| Profil + Paramètres complets, suppression de compte RGPD opérationnelle | android-developer |
| i18n FR/EN : 0 chaîne codée en dur (CI lint), langue à chaud, topics FCM alignés | android-developer |
| Crashlytics + Analytics remontent (crash test validé), AAB signé uploadé piste interne | devops-engineer |
| Fiche Store + Data Safety + évaluation contenu prêtes pour soumission | product-designer + devops |

## 7. 🏁 Bilan du projet complet (Sprints 1 → 4)

```text
✅ Mono-repo + CI/CD multi-agents Claude Code (7 agents)        [S1]
✅ Home / Détail / Lecteur vertical / Wallet / Bibliothèque / Profil / Parrainage
✅ Backend sécurisé : ledger pièces, unlock atomique, billing Google validé serveur,
   streaming signé, push FCM                                    [S1-S4]
✅ Pipeline IA : 1 série de 50 épisodes/jour, budget guard, reprise idempotente,
   alertes Slack, ~30-70 €/jour                                 [S1-S3]
✅ Production : beta fermée → rollout progressif 10→50→100 %     [S4]
```

**Backlog v1.1 (idées post-lancement) :** onglet « Pour Vous » avec recommandations par genre regardé · A/B testing des prix de pièces (500 vs 400) · FR→EN des dramas générés (dubbing TTS multilingue) · mini-jeu « choisis la suite » (interactivité par votes) · web app mobile (PWA) réutilisant l'API · programme de créateurs (UGC avec validation showrunner).