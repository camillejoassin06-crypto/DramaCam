# 🚀 DramaForge — Sprint 1 : Implémentation multi-agents

> Exécution concrète du blueprint : bootstrapping du mono-repo (devops-engineer), écran Home complet (android-developer), schéma Prisma + service Wallet (backend-developer), lecteur vertical (android-developer) et bibliothèque de prompts du pipeline IA (ai-pipeline-engineer).

**Ordre de dispatch recommandé dans Claude Code :**

```text
1. devops-engineer     -> bootstrapping mono-repo + CI (fichiers de ce document, section 1)
2. backend-developer   -> prisma/schema.prisma + wallet (sections 3)
3. android-developer   -> Home + design system (section 2)
4. android-developer   -> lecteur vertical (section 4)
5. ai-pipeline-engineer -> prompts + zod (section 5)
6. qa-engineer         -> revue : ./gradlew assembleDebug, npm test, npx tsc --noEmit
```

---

## 1. 🛠️ devops-engineer — Bootstrapping du mono-repo

### 1.1 Arborescence cible après bootstrap

```text
dramaforge/
├── CLAUDE.md
├── package.json                  # npm workspaces (backend + pipeline + shared)
├── .gitignore
├── .env.example
├── tsconfig.base.json
├── .github/
│   ├── workflows/ci.yml
│   ├── workflows/daily-drama.yml  # (déjà défini dans le blueprint, section 5.7)
│   └── pull_request_template.md
├── app/                          # Android (Gradle)
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle/libs.versions.toml
│   └── src/main/...
├── backend/
│   ├── package.json
│   ├── nest-cli.json
│   ├── prisma/
│   │   ├── schema.prisma
│   │   └── seed.ts
│   └── src/
│       ├── main.ts
│       ├── app.module.ts
│       ├── prisma/prisma.module.ts
│       ├── auth/ (auth.module.ts, auth.service.ts, ...)
│       ├── catalog/ (catalog.module.ts, catalog.service.ts, ...)
│       ├── wallet/ (wallet.module.ts, wallet.service.ts, wallet.controller.ts)
│       ├── stream/ (stream.service.ts)   # URLs HLS signées
│       └── admin/ (admin.controller.ts)   # import pipeline IA
├── pipeline/
│   ├── package.json
│   ├── src/
│   │   ├── prompts/ (concept.ts, bible.ts, episode.ts, quality.ts)
│   │   ├── schemas/ (zod)
│   │   ├── 01-concept.ts ... 04-render.ts
│   │   └── generate-daily.ts
└── shared/                       # types TS communs backend/pipeline
    └── src/types.ts
```

### 1.2 `package.json` (racine — workspaces)

```json
{
  "name": "dramaforge",
  "private": true,
  "workspaces": ["backend", "pipeline", "shared"],
  "scripts": {
    "backend:dev": "npm -w backend run start:dev",
    "backend:test": "npm -w backend run test:e2e",
    "db:migrate": "npm -w backend run prisma:migrate",
    "db:studio": "npm -w backend run prisma:studio",
    "pipeline:generate": "npm -w pipeline run generate",
    "pipeline:dry": "npm -w pipeline run generate -- --dry-run",
    "typecheck": "tsc -b backend pipeline shared",
    "test": "npm -w backend run test && npm -w pipeline run test"
  },
  "devDependencies": {
    "typescript": "^5.6.3",
    "vitest": "^2.1.3",
    "@types/node": "^22.7.5"
  }
}
```

### 1.3 `tsconfig.base.json`

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true,
    "declaration": true,
    "sourceMap": true
  }
}
```

### 1.4 `.env.example` (à copier en `.env`, jamais commité)

```bash
# ---- Anthropic (pipeline de génération) ----
ANTHROPIC_API_KEY=sk-ant-...
# Optionnel : modèle de la bible (qualité des arcs)
BIBLE_MODEL=claude-opus-4-6
SCRIPT_MODEL=claude-sonnet-4-5

# ---- Média ----
IMAGE_API_KEY=...
IMAGE_API_URL=...
TTS_API_KEY=...
TTS_PROVIDER=elevenlabs

# ---- Stockage / CDN ----
R2_ACCOUNT_ID=...
R2_ACCESS_KEY=...
R2_SECRET_KEY=...
R2_BUCKET=dramaforge-media
CDN_BASE_URL=https://cdn.dramaforge.app

# ---- Backend ----
DATABASE_URL=postgresql://dramaforge:...@localhost:5432/dramaforge
JWT_SECRET=...
ADMIN_API_KEY=...            # clé de service du pipeline IA
GOOGLE_PLAY_SERVICE_ACCOUNT_JSON=./secrets/play-service-account.json

# ---- Android (secrets injectés par CI, jamais en clair) ----
# ANDROID_KEYSTORE_BASE64 / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD / SIGNING_STORE_PASSWORD
```

### 1.5 `.github/workflows/ci.yml`

```yaml
name: CI
on:
  pull_request:
  push: { branches: [main] }

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  backend:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env: { POSTGRES_PASSWORD: test, POSTGRES_DB: dramaforge_test }
        options: >-
          --health-cmd pg_isready --health-interval 5s --health-timeout 5s --health-retries 10
        ports: ['5432:5432']
    env:
      DATABASE_URL: postgresql://postgres:test@localhost:5432/dramaforge_test
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 22, cache: npm }
      - run: npm ci
      - run: npm -w backend run prisma:generate
      - run: npm -w backend run prisma:migrate -- --skip-seed
      - run: npm -w backend run build
      - run: npm -w backend run test
      - run: npm -w backend run test:e2e

  pipeline:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 22, cache: npm }
      - run: npm ci
      - run: npm run typecheck
      - run: npm -w pipeline run test

  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - uses: gradle/actions/setup-gradle@v4
      - name: Build debug APK
        working-directory: app
        run: ./gradlew assembleDebug lint
      - uses: actions/upload-artifact@v4
        if: github.ref == 'refs/heads/main'
        with:
          name: dramaforge-debug-apk
          path: app/app/build/outputs/apk/debug/*.apk

  release:
    if: startsWith(github.ref, 'refs/tags/v')
    needs: [backend, android]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - name: Decode keystore
        run: echo "${{ secrets.ANDROID_KEYSTORE_BASE64 }}" | base64 -d > app/dramaforge.keystore
      - name: Build signed AAB + APK
        working-directory: app
        env:
          SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
          SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
          SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}
        run: ./gradlew bundleRelease assembleRelease
      - uses: softprops/action-gh-release@v2
        with:
          files: |
            app/app/build/outputs/bundle/release/app-release.aab
            app/app/build/outputs/apk/release/app-release.apk
```

### 1.6 `app/settings.gradle.kts` + root `build.gradle.kts`

```kotlin
// app/settings.gradle.kts
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { google(); mavenCentral() }
}
rootProject.name = "DramaForge"
include(":app")
```

```kotlin
// app/build.gradle.kts (racine Android)
plugins {
    id("com.android.application") version "8.7.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
}
```

**Checklist devops-engineer après bootstrap :**

- [ ] `git init && git commit -m "chore: bootstrap mono-repo (devops-engineer)"`
- [ ] `npm install && npm run typecheck` verts
- [ ] `docker compose up -d postgres` + `npm run db:migrate` OK
- [ ] `./gradlew assembleDebug` OK
- [ ] CI verte sur la PR de bootstrap
- [ ] Secrets GitHub créés : `ANTHROPIC_API_KEY`, `ANDROID_KEYSTORE_BASE64`, `SIGNING_*`, `ADMIN_API_KEY`, `R2_*`

---

## 2. 📱 android-developer — Écran Home complet

### 2.1 Modèle domaine `domain/model/Drama.kt`

```kotlin
package com.dramaforge.app.domain.model

data class Drama(
    val id: String,
    val title: String,
    val emotionalTag: String?,        // "Amour interdit", "Revanche"...
    val coverUrl: String,
    val episodeCount: Int,
    val isExclusive: Boolean,
    val isNew: Boolean,
    val rating: Float,
    val views: Long,
    val totalCoinsToFinish: Int?,     // prix total de la série (affiché parfois)
)

data class Category(val id: String, val nameFr: String, val emoji: String, val coverUrl: String)
```

### 2.2 DTO réseau + mapping `core/network/DramaDTO.kt`

```kotlin
package com.dramaforge.app.core.network

import com.dramaforge.app.domain.model.Category
import com.dramaforge.app.domain.model.Drama

data class DramaDTO(
    val id: String,
    val title: String,
    val emotionalTag: String? = null,
    val coverUrl: String,
    val episodeCount: Int,
    val exclusive: Boolean = false,
    val isNew: Boolean = false,
    val rating: Float = 0f,
    val views: Long = 0,
) {
    fun toDomain() = Drama(
        id = id, title = title, emotionalTag = emotionalTag, coverUrl = coverUrl,
        episodeCount = episodeCount, isExclusive = exclusive, isNew = isNew,
        rating = rating, views = views, totalCoinsToFinish = null,
    )
}

data class PageDTO<T>(val items: List<T>, val nextCursor: String?)

data class CategoryDTO(val id: String, val nameFr: String, val emoji: String, val coverUrl: String) {
    fun toDomain() = Category(id, nameFr, emoji, coverUrl)
}
```

### 2.3 Repository `domain/repository/CatalogRepository.kt`

```kotlin
package com.dramaforge.app.domain.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.dramaforge.app.core.network.ApiService
import com.dramaforge.app.core.network.CategoryDTO
import com.dramaforge.app.domain.model.Category
import com.dramaforge.app.domain.model.Drama
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepository @Inject constructor(private val api: ApiService) {

    val trending: Flow<PagingData<Drama>> =
        paged { cursor -> api.getTrending(cursor) }

    fun ranking(window: String): Flow<List<Drama>> = flowList { api.getRanking(window) }
    fun byCategory(categoryId: String): Flow<PagingData<Drama>> =
        paged { cursor -> api.getByCategory(categoryId, cursor) }

    suspend fun categories(): List<Category> = api.getCategories().map { it.toDomain() }
    suspend fun newReleases(cursor: String?) = api.getNew(cursor)

    private fun paged(fetch: suspend (String?) -> PageDTO<DramaDTO>): Flow<PagingData<Drama>> =
        Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
            CatalogPagingSource(fetch)
        }.flow
}
```

*(Les helpers `flowList`, `CatalogPagingSource` sont standard : `PagingSource` qui appelle `fetch(cursor)` et mappe les `DramaDTO.toDomain()`.)*

### 2.4 UiState + ViewModel `feature/home/HomeViewModel.kt`

```kotlin
package com.dramaforge.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.dramaforge.app.domain.model.Category
import com.dramaforge.app.domain.model.Drama
import com.dramaforge.app.domain.repository.CatalogRepository
import com.dramaforge.app.domain.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val trending: List<Drama> = emptyList(),
    val ranking: List<Drama> = emptyList(),       // fenêtre "daily"
    val newReleases: List<Drama> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedCategory: Category? = null,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    walletRepository: WalletRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val coinBalance: StateFlow<Int> = walletRepository.coinBalance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val trendingPager = catalog.trending.cachedIn(viewModelScope)

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val trending = catalog.trending.firstPage()
            val ranking = catalog.ranking("daily").first()
            val new = catalog.newReleases(null).items.map { it.toDomain() }
            val categories = catalog.categories()
            _uiState.update {
                HomeUiState(isLoading = false, trending = trending, ranking = ranking,
                            newReleases = new, categories = categories)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message ?: "Erreur réseau") }
        }
    }

    fun selectCategory(category: Category?) { _uiState.update { it.copy(selectedCategory = category) } }
    fun clearError() { _uiState.update { it.copy(error = null) } }
}
```

### 2.5 Design system `core/design/Badges.kt`

```kotlin
package com.dramaforge.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color) {
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CoinChip(coinCount: Int, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("🪙", fontSize = 12.sp)
        Text(coinCount.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
```

### 2.6 Écran complet `feature/home/HomeScreen.kt`

```kotlin
package com.dramaforge.app.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dramaforge.app.core.design.Badge
import com.dramaforge.app.core.design.CoinChip
import com.dramaforge.app.core.design.ErrorState
import com.dramaforge.app.core.design.LoadingState
import com.dramaforge.app.domain.model.Category
import com.dramaforge.app.domain.model.Drama

private val TABS = listOf("Tendance", "Classement", "Inédit", "Catégories")

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onDramaClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onWalletClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val coins by viewModel.coinBalance.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        HomeTopBar(coinCount = coins, onSearchClick = onSearchClick, onWalletClick = onWalletClick)

        // Onglets : "CATÉGORIES ▾" ouvre un menu déroulant au lieu d'un contenu
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            edgePadding = 16.dp,
        ) {
            TABS.forEachIndexed { i, label ->
                Tab(
                    selected = selectedTab == i,
                    onClick = {
                        if (label == "Catégories") categoryMenuOpen = !categoryMenuOpen
                        else { categoryMenuOpen = false; selectedTab = i }
                    },
                    text = { Text(if (label == "Catégories") "Catégories ▾" else label) },
                )
            }
        }

        DropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
            state.categories.forEach { cat ->
                DropdownMenuItem(
                    text = { Text("${cat.emoji}  ${cat.nameFr}") },
                    onClick = { viewModel.selectCategory(cat); selectedTab = 0; categoryMenuOpen = false },
                )
            }
        }

        when {
            state.isLoading -> LoadingState()
            state.error != null -> ErrorState(message = state.error!!, onRetry = { viewModel.refresh() })
            else -> when (selectedTab) {
                0 -> TrendingTab(state, viewModel, onDramaClick)
                1 -> RankingTab(state, onDramaClick)
                2 -> NewTab(state, onDramaClick)
                else -> TrendingTab(state, viewModel, onDramaClick) // Catégories -> retour sur Tendance filtré
            }
        }
    }
}

// ---- TENDANCE : carrousel "Chaud maintenant" + grille ----
@Composable
private fun TrendingTab(state: HomeUiState, viewModel: HomeViewModel, onDramaClick: (String) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        state.selectedCategory?.let { cat ->
            item(span = { GridItemSpan(2) }) { CategoryHeader(cat) }
        }
        items(state.trending, key = { it.id }) { drama ->
            CoverCard(drama, onDramaClick)
        }
    }
}

// ---- CLASSEMENT : Top 10 avec gros numéros (daily/weekly switch) ----
@Composable
private fun RankingTab(state: HomeUiState, onDramaClick: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Row(Modifier.padding(horizontal = 16.dp)) {
                RankWindowSwitcher(onWindowChange = { /* vm: ranking(window) */ })
            }
        }
        rowItems(state.ranking.take(10)) { drama ->
            val rank = state.ranking.indexOf(drama) + 1
            RankingRow(rank = rank, drama = drama, onClick = { onDramaClick(drama.id) })
        }
    }
}

@Composable
private fun RankingRow(rank: Int, drama: Drama, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("#$rank", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        CoverCard(drama, onClick, Modifier.weight(1f).padding(start = 12.dp))
    }
}

// ---- INÉDIT : bande horizontale "Nouveautés du jour" + grille ----
@Composable
private fun NewTab(state: HomeUiState, onDramaClick: (String) -> Unit) {
    LazyColumn {
        item {
            LazyRow(contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems(state.newReleases) { drama ->
                    CoverCard(drama, { onDramaClick(drama.id) }, Modifier.width(140.dp))
                }
            }
        }
        item {
            LazyVerticalGrid(columns = GridCells.Fixed(2),
                modifier = Modifier.heightIn(min = 0.dp)) {
                items(state.newReleases.drop(6), key = { it.id }) { CoverCard(it, onDramaClick) }
            }
        }
    }
}

// ---- Carte de couverture commune (badges EXCLUSIVE / NOUVEAU + tag émotionnel) ----
@Composable
private fun CoverCard(drama: Drama, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium) {
        Box {
            AsyncImage(model = drama.coverUrl, contentDescription = drama.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.72f))
            when {
                drama.isExclusive -> Badge("EXCLUSIVE", Modifier.align(Alignment.TopStart).padding(8.dp), Color(0xFF6C2BD9))
                drama.isNew -> Badge("NOUVEAU", Modifier.align(Alignment.TopStart).padding(8.dp), Color(0xFFE53935))
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(10.dp)) {
                Text(drama.title, color = Color.White, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    drama.emotionalTag?.let { Text(it, color = Color(0xFFFFD54F), style = MaterialTheme.typography.labelSmall) }
                    Text("★ ${"%.1f".format(drama.rating)}", color = Color.White,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(coinCount: Int, onSearchClick: () -> Unit, onWalletClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("DramaForge", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        OutlinedTextFieldPlaceholder(onClick = onSearchClick)   // "Rechercher un drama..." (cliquable, style pill)
        Spacer(Modifier.width(8.dp))
        CoinChip(coinCount = coinCount, onClick = onWalletClick)
    }
}
```

**Definition of Done android-developer (Home) :** `assembleDebug` vert · shimmer pendant `isLoading` · `LazyVerticalGrid` clé unique par `drama.id` · titres tronqués 2 lignes · badges localisés FR · test unitaire `HomeViewModelTest` (fake repository, états loading/success/error).

---

## 3. 🗄️ backend-developer — Schéma Prisma + Service Wallet

### 3.1 `backend/prisma/schema.prisma`

```prisma
generator client {
  provider = "prisma-client-js"
}

datasource db {
  provider = "postgresql"
  url      = env("DATABASE_URL")
}

model User {
  id          String    @id @default(cuid())
  email       String?   @unique
  phone       String?   @unique
  displayName String?
  locale      String    @default("fr")
  firebaseUid String?   @unique
  wallet      Wallet?
  library     LibraryItem[]
  unlocks     Unlock[]
  progress    WatchProgress[]
  comments    Comment[]
  referralsGiven  Referral[] @relation("referrer")
  referralsTaken  Referral[] @relation("friend")
  subscription    Subscription?
  createdAt    DateTime  @default(now())
  updatedAt    DateTime  @updatedAt
}

model Wallet {
  id         String    @id @default(cuid())
  userId     String    @unique
  user       User      @relation(fields: [userId], references: [id])
  coins      Int       @default(0)   // pièces achetées (n'expirent pas)
  bonusCoins Int       @default(0)   // pièces bonus (expirent, consommées en premier)
  bonusExpiresAt DateTime?
  txns       CoinTxn[]
  updatedAt  DateTime  @updatedAt

  // Le solde affiché ne peut JAMAIS être négatif
  @@index([userId])
}

model CoinTxn {
  id        String   @id @default(cuid())
  walletId  String
  wallet    Wallet   @relation(fields: [walletId], references: [id])
  amount    Int                // positif = crédit, négatif = débit
  kind      String             // PURCHASE | BONUS_DAILY | UNLOCK | REFERRAL | REFUND | AD_REWARD
  ref       String?            // episodeId / purchaseToken / referralId
  createdAt DateTime @default(now())

  @@index([walletId, createdAt])
}

model Category {
  id     String @id @default(cuid())
  slug   String @unique
  nameFr String
  emoji  String
  dramas Drama[]
}

model Drama {
  id           String   @id @default(cuid())
  title        String
  emotionalTag String?
  categoryId   String?
  category     Category? @relation(fields: [categoryId], references: [id])
  exclusive    Boolean  @default(false)
  isNew        Boolean  @default(true)
  status       String   @default("ONGOING")  // ONGOING | COMPLETED
  episodeCount Int      @default(0)
  coverUrl     String
  rating       Float    @default(0)
  views        BigInt   @default(0)
  releasedAt   DateTime @default(now())
  episodes     Episode[]

  @@index([exclusive, releasedAt])
  @@index([categoryId])
}

model Episode {
  id          String   @id @default(cuid())
  dramaId     String
  drama       Drama    @relation(fields: [dramaId], references: [id], onDelete: Cascade)
  number      Int
  durationSec Int      @default(150)
  priceCoins  Int      @default(500)
  free        Boolean  @default(false)
  hlsUrl      String
  vttUrl      String?
  thumbnailUrl String?
  unlocks     Unlock[]
  progress    WatchProgress[]
  comments    Comment[]

  @@unique([dramaId, number])
}

model Unlock {
  id          String   @id @default(cuid())
  userId      String
  user        User     @relation(fields: [userId], references: [id])
  episodeId   String
  episode     Episode  @relation(fields: [episodeId], references: [id])
  coinsSpent  Int      @default(0)
  createdAt   DateTime @default(now())

  @@unique([userId, episodeId])   // garantie anti double-unlock au niveau base
}

model WatchProgress {
  userId     String
  episodeId  String
  positionSec Int     @default(0)
  episode     Episode @relation(fields: [episodeId], references: [id], onDelete: Cascade)
  updatedAt   DateTime @updatedAt

  @@id([userId, episodeId])
}

model LibraryItem {
  id      String  @id @default(cuid())
  userId  String
  user    User    @relation(fields: [userId], references: [id])
  dramaId String
  kind    String  @default("favorite")   // favorite | history | download
  addedAt DateTime @default(now())

  @@unique([userId, dramaId, kind])
}

model Comment {
  id        String   @id @default(cuid())
  userId    String
  user      User     @relation(fields: [userId], references: [id])
  episodeId String
  episode   Episode  @relation(fields: [episodeId], references: [id], onDelete: Cascade)
  body      String   @db.VarChar(500)
  createdAt DateTime @default(now())

  @@index([episodeId, createdAt])
}

model Referral {
  id          String   @id @default(cuid())
  referrerId  String
  referrer    User     @relation("referrer", fields: [referrerId], references: [id])
  friendId    String
  friend      User     @relation("friend", fields: [friendId], references: [id])
  rewardCoins Int      @default(0)
  createdAt   DateTime @default(now())

  @@unique([friendId])          // un ami ne peut être parrainé qu'une fois
}

model Subscription {
  id         String   @id @default(cuid())
  userId     String   @unique
  user       User     @relation(fields: [userId], references: [id])
  platform   String   @default("google_play")
  productId  String
  status     String   @default("active")  // active | expired | canceled
  expiresAt  DateTime
  purchaseToken String @unique
  createdAt  DateTime @default(now())
}
```

### 3.2 `backend/src/wallet/wallet.service.ts` — débit atomique

```typescript
import { Injectable, ConflictException, BadRequestException } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';

export class InsufficientCoinsError extends BadRequestException {
  constructor(public readonly needed: number, public readonly available: number) {
    super(`Solde insuffisant : ${needed} pièces requises, ${available} disponibles`);
  }
}

@Injectable()
export class WalletService {
  constructor(private readonly prisma: PrismaService) {}

  /** Solde consolidé (bonus d'abord, puis pièces achetées). */
  private total(wallet: { coins: number; bonusCoins: number; bonusExpiresAt: Date | null }) {
    const bonusValid = wallet.bonusExpiresAt ? wallet.bonusExpiresAt > new Date() : true;
    const bonus = bonusValid ? wallet.bonusCoins : 0;
    return { bonus, coins: wallet.coins, total: bonus + wallet.coins };
  }

  async unlockEpisode(userId: string, episodeId: string) {
    // Transaction interactive + verrou pessimiste : protège contre le double-clic
    // et les appels concurrents.
    return this.prisma.$transaction(async (tx) => {
      const episode = await tx.episode.findUniqueOrThrow({ where: { id: episodeId } });

      const already = await tx.unlock.findUnique({
        where: { userId_episodeId: { userId, episodeId } },
      });
      if (already || episode.free) return { ok: true, charged: 0, idempotent: true };

      const vip = await this.hasActiveVip(tx, userId);
      if (vip) {
        await tx.unlock.create({ data: { userId, episodeId, coinsSpent: 0 } });
        return { ok: true, charged: 0, vip: true };
      }

      // SELECT ... FOR UPDATE : sérialise les débits concurrents sur ce wallet
      const [wallet] = await tx.$queryRaw<{ id: string; coins: number; bonusCoins: number;
        bonusExpiresAt: Date | null }[]>`
        SELECT id, coins, "bonusCoins", "bonusExpiresAt"
        FROM "Wallet" WHERE "userId" = ${userId} FOR UPDATE`;
      if (!wallet) throw new BadRequestException('Wallet introuvable');

      const price = episode.priceCoins;
      const { bonus, coins, total } = this.total(wallet);
      if (total < price) throw new InsufficientCoinsError(price, total);

      // Règle DramaWave : les pièces bonus sont TOUJOURS consommées en premier.
      const fromBonus = Math.min(bonus, price);
      const fromCoins = price - fromBonus;

      await tx.wallet.update({
        where: { id: wallet.id },
        data: { bonusCoins: bonus - fromBonus, coins: coins - fromCoins },
      });
      await tx.coinTxn.create({
        data: { walletId: wallet.id, amount: -price, kind: 'UNLOCK', ref: episodeId },
      });
      // La contrainte @@unique(userId, episodeId) rejette toute course résiduelle.
      try {
        await tx.unlock.create({ data: { userId, episodeId, coinsSpent: price } });
      } catch {
        // Un appel concurrent a déjà débloqué : on annule le débit.
        await tx.wallet.update({ where: { id: wallet.id },
          data: { bonusCoins: bonus, coins } });
        await tx.coinTxn.deleteMany({ where: { walletId: wallet.id, kind: 'UNLOCK', ref: episodeId } });
        throw new ConflictException('Épisode déjà débloqué');
      }
      return { ok: true, charged: price, remaining: total - price };
    }, { isolationLevel: 'Serializable' });
  }

  private async hasActiveVip(tx: any, userId: string) {
    const sub = await tx.subscription.findFirst({
      where: { userId, status: 'active', expiresAt: { gt: new Date() } },
    });
    return !!sub;
  }

  /** Crédit de pièces après validation serveur d'un achat Google Play. */
  async creditPurchase(walletId: string, amount: number, kind: string, ref: string) {
    return this.prisma.$transaction(async (tx) => {
      await tx.wallet.update({ where: { id: walletId },
        data: { coins: { increment: amount } } });
      await tx.coinTxn.create({ data: { walletId, amount, kind, ref } });
    });
  }

  /** Bonus quotidien (50 pièces, expirent sous 48 h — filet de rétention type DramaWave). */
  async grantDailyBonus(userId: string) {
    return this.prisma.$transaction(async (tx) => {
      const today = new Date(); today.setHours(0, 0, 0, 0);
      const claimed = await tx.coinTxn.findFirst({
        where: { wallet: { userId }, kind: 'BONUS_DAILY', createdAt: { gte: today } },
      });
      if (claimed) return { ok: false, reason: 'ALREADY_CLAIMED' as const };
      const expiry = new Date(Date.now() + 48 * 3600 * 1000);
      const wallet = await tx.wallet.update({ where: { userId },
        data: { bonusCoins: { increment: 50 }, bonusExpiresAt: expiry } });
      await tx.coinTxn.create({ data: { walletId: wallet.id, amount: 50, kind: 'BONUS_DAILY' } });
      return { ok: true, bonusCoins: wallet.bonusCoins, total: this.total(wallet).total };
    });
  }
}
```

### 3.3 `backend/src/wallet/wallet.controller.ts`

```typescript
import { Body, Controller, Get, Param, Post, Req, UseGuards } from '@nestjs/common';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { WalletService } from './wallet.service';

@Controller('wallet')
@UseGuards(JwtAuthGuard)
export class WalletController {
  constructor(private readonly wallet: WalletService) {}

  @Get()
  async getWallet(@Req() req: any) {
    return this.wallet.getSummary(req.user.id);
  }

  @Post('unlock')
  async unlock(@Req() req: any, @Body() body: { episodeId: string }) {
    return this.wallet.unlockEpisode(req.user.id, body.episodeId);
  }

  @Post('bonus/daily')
  async dailyBonus(@Req() req: any) {
    return this.wallet.grantDailyBonus(req.user.id);
  }

  @Get('transactions')
  async transactions(@Req() req: any) {
    return this.wallet.transactions(req.user.id);
  }
}
```

### 3.4 Test e2e anti double-dépense `backend/test/wallet.e2e-spec.ts`

```typescript
import { Test } from '@nestjs/testing';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { PrismaService } from '../src/prisma/prisma.service';

describe('Wallet — déverrouillage (POST /wallet/unlock)', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  let token: string;
  let episodeId: string;

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({ /* AppModule + fixtures */ }).compile();
    app = moduleRef.createNestApplication(); await app.init();
    prisma = app.get(PrismaService);
    ({ token, episodeId } = await seedUserWithCoins(prisma, { coins: 1000 }));
  });

  it('débite les pièces bonus avant les pièces achetées', async () => {
    await seedBonus(app, 200); // 200 pièces bonus
    const res = await request(app.getHttpServer())
      .post('/wallet/unlock').set('Authorization', `Bearer ${token}`)
      .send({ episodeId }).expect(201);
    expect(res.body.charged).toBe(500);
    const wallet = await getWallet(app, token);
    expect(wallet.bonusCoins).toBe(0);     // bonus consommé en premier
    expect(wallet.coins).toBe(700);        // 1000 - (500 - 200)
  });

  it('rejette deux déverrouillages concurrents du même épisode (anti double-dépense)', async () => {
    const [a, b] = await Promise.allSettled([
      unlock(app, token, episodeId2),
      unlock(app, token, episodeId2),
    ]);
    const ok = [a, b].filter(r => r.status === 'fulfilled' && (r.value as any).body.ok);
    expect(ok).toHaveLength(1); // exactement UN débit, l'autre est idempotent ou 409
    const wallet = await getWallet(app, token);
    expect(wallet.coins).toBe(expect.any(Number));
    // Vérifier qu'un seul CoinTxn UNLOCK a été créé pour episodeId2
    const txns = await prisma.coinTxn.findMany({ where: { kind: 'UNLOCK', ref: episodeId2 } });
    expect(txns).toHaveLength(1);
  });

  it('retourne 400 avec le solde manquant si pièces insuffisantes', async () => {
    const res = await request(app.getHttpServer())
      .post('/wallet/unlock').set('Authorization', `Bearer ${token}`)
      .send({ episodeId: episodeId3 }).expect(400);
    expect(res.body.message).toContain('Solde insuffisant');
  });
});
```

**Definition of Done backend-developer :** `prisma migrate dev` OK · `npm -w backend run test:e2e` vert (3 cas ci-dessus) · bonus consommé en premier · contrainte `@@unique(userId, episodeId)` en place · aucune mutation de solde hors transaction.

---

## 4. 🎬 android-developer — Lecteur vertical complet

### 4.1 `feature/player/PlayerViewModel.kt` — pool de 3 players

```kotlin
package com.dramaforge.app.feature.player

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.dramaforge.app.core.network.ApiService
import com.dramaforge.app.domain.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import javax.inject.Inject

data class PlayerUiState(
    val isLoading: Boolean = true,
    val episodes: List<EpisodeItem> = emptyList(),
    val error: String? = null,
    val walletCoins: Int = 0,
)

data class EpisodeItem(
    val id: String,
    val number: Int,
    val dramaTitle: String,
    val isFree: Boolean,
    val isUnlocked: Boolean,
    val isLocked: Boolean = !isFree && !isUnlocked,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val app: Application,
    private val api: ApiService,
    private val walletRepository: WalletRepository,
) : ViewModel() {

    companion object { private const MAX_ACTIVE_PLAYERS = 3 }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Pool LRU : le player de la page courante + 1 avant + 1 après.
    private val players = object : LinkedHashMap<String, ExoPlayer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExoPlayer>?): Boolean {
            val evict = size > MAX_ACTIVE_PLAYERS
            if (evict) eldest?.value?.release()
            return evict
        }
    }
    private val streamUrlCache = mutableMapOf<String, Pair<String, String?>>() // episodeId -> (hls, vtt)

    fun load(dramaId: String, startEpisodeId: String) = viewModelScope.launch {
        try {
            val eps = api.getEpisodes(dramaId)
            val wallet = api.getWallet()
            _uiState.value = PlayerUiState(
                isLoading = false,
                episodes = eps.map {
                    EpisodeItem(it.id, it.number, it.dramaTitle, it.free,
                        wallet.unlockedEpisodeIds.contains(it.id))
                },
                walletCoins = wallet.totalCoins,
            )
            prefetch(startEpisodeId)
        } catch (e: Exception) {
            _uiState.value = PlayerUiState(error = e.message)
        }
    }

    /** URL de stream signée (TTL court) : jamais stockée durablement, re-fetch au besoin. */
    private suspend fun prefetch(episodeId: String) {
        if (streamUrlCache.containsKey(episodeId)) return
        val url = api.getStreamUrl(episodeId)
        streamUrlCache[episodeId] = url.hls to url.vtt
    }

    fun playerFor(episodeId: String): ExoPlayer =
        players[episodeId] ?: createPlayer(episodeId).also { players[episodeId] = it }

    private fun createPlayer(episodeId: String): ExoPlayer {
        val (hls, vtt) = streamUrlCache[episodeId]!!
        val builder = androidx.media3.common.MediaItem.Builder().setUri(hls)
        vtt?.let { builder.setSubtitleConfigurations(listOf(
            androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it))
                .setMimeType(androidx.media3.common.MimeTypes.TEXT_VTT)
                .setLanguage("fr").build())) }
        return ExoPlayer.Builder(app).build().apply {
            setMediaItem(builder.build())
            playWhenReady = true
            videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            prepare()
        }
    }

    fun onPageChanged(currentIndex: Int) {
        val eps = _uiState.value.episodes
        viewModelScope.launch {
            // Préchargement des URL des épisodes voisins (cache de métadonnées)
            (currentIndex - 1..currentIndex + 1).forEach { i ->
                if (i in eps.indices && !eps[i].isLocked) prefetch(eps[i].id)
            }
            // Pause des players hors fenêtre active (économie batterie/données)
            players.keys.toList().forEach { id ->
                if (id != eps.getOrNull(currentIndex)?.id) players[id]?.playWhenReady = false
            }
            players[eps[currentIndex].id]?.playWhenReady = true
        }
    }

    fun unlockEpisode(episodeId: String) = viewModelScope.launch {
        walletRepository.unlockEpisode(episodeId)
            .onSuccess {
                _uiState.update { s -> s.copy(
                    walletCoins = it.totalCoins,
                    episodes = s.episodes.map { e ->
                        if (e.id == episodeId) e.copy(isUnlocked = true, isLocked = false) else e }) }
                prefetch(episodeId)
            }
            .onFailure { _uiState.update { s -> s.copy(error = "Déblocage impossible : ${it.message}") } }
    }

    fun saveProgress(episodeId: String, positionSec: Int) = viewModelScope.launch {
        runCatching { api.saveProgress(WatchProgress(episodeId, positionSec)) }
    }

    fun releasePlayers() { players.values.forEach { it.release() }; players.clear() }

    override fun onCleared() { releasePlayers(); super.onCleared() }
}
```

### 4.2 `feature/player/PlayerScreen.kt` — écran complet

```kotlin
package com.dramaforge.app.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.pager.VerticalPager
import androidx.compose.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dramaforge.app.core.player.PlayerSurfaceView

@Composable
fun PlayerScreen(
    dramaId: String,
    startEpisodeId: String,
    onBack: () -> Unit,
    onGoWallet: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(dramaId, startEpisodeId) { viewModel.load(dramaId, startEpisodeId) }
    DisposableEffect(Unit) { onDispose { viewModel.releasePlayers() } }

    val episodes = state.episodes
    if (state.isLoading) { LoadingOverlay(); return }
    if (episodes.isEmpty()) { EmptyOverlay(state.error); return }

    val startIndex = episodes.indexOfFirst { it.id == startEpisodeId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { episodes.size }

    // Signaler les changements de page (pause/préchargement) + sauvegarde de progression
    LaunchedEffect(pagerState.currentPage) { viewModel.onPageChanged(pagerState.currentPage) }

    val currentEpisode = episodes[pagerState.currentPage]
    var commentsVisible by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pagerState, beyondViewportPageCount = 1) { page ->
            val episode = episodes[page]
            Box(Modifier.fillMaxSize()) {
                if (!episode.isLocked) {
                    AndroidView(
                        factory = { ctx -> PlayerSurfaceView(ctx)
                            .apply { setPlayer(viewModel.playerFor(episode.id)) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Les players recyclent leur surface ; ne jamais réattacher une surface libérée.
                    LaunchedEffect(episode.id) {
                        snapshotFlow { pagerState.currentPage }
                            .collect { current ->
                                if (current == page) viewModel.saveProgress(episode.id, positionSec = 0)
                            }
                    }
                } else {
                    LockedOverlay(
                        episodeNumber = episode.number,
                        priceCoins = 500,
                        walletCoins = state.walletCoins,
                        onUnlock = { viewModel.unlockEpisode(episode.id) },
                        onGoWallet = onGoWallet,
                    )
                }
            }
        }

        // Overlay supérieur : titre, épisode, retour
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("←", color = Color.White) }
            Column {
                Text(currentEpisode.dramaTitle, color = Color.White)
                Text("Épisode ${currentEpisode.number} / ${episodes.size}",
                     color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelSmall)
            }
        }

        // Overlay droit : commentaires flottants (style TikTok), pouces, partage
        Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
            .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { commentsVisible = !commentsVisible }) {
                Text("💬", color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            IconButton(onClick = { /* partage deep link */ }) { Text("↗", color = Color.White) }
        }

        if (commentsVisible) {
            CommentsBottomSheet(episodeId = currentEpisode.id,
                onDismiss = { commentsVisible = false })
        }
    }
}

@Composable
fun LockedOverlay(episodeNumber: Int, priceCoins: Int, walletCoins: Int,
                  onUnlock: () -> Unit, onGoWallet: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xCC000000)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("🔒", Modifier.padding(bottom = 12.dp))
        Text("Épisode $episodeNumber verrouillé", color = Color.White,
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        if (walletCoins >= priceCoins) {
            Button(onClick = onUnlock) { Text("Débloquer · 🪙 $priceCoins") }
        } else {
            Button(onClick = onGoWallet) { Text("Recharger des pièces") }
            Text("Il vous manque ${priceCoins - walletCoins} pièces",
                color = Color(0xFFFFD54F), style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onGoWallet) { Text("Passer VIP · Tout débloquer", color = Color(0xFFB39DFF)) }
    }
}

@Composable
fun CommentsBottomSheet(episodeId: String, onDismiss: () -> Unit) {
    var comments by remember { mutableStateOf(emptyList<CommentUI>()) }
    LaunchedEffect(episodeId) { /* Charger via ApiService.getComments(episodeId) */ }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn {
            items(comments, key = { it.id }) { CommentBubble(it) }
        }
    }
}
```

**Points QA du lecteur :** max 3 ExoPlayer vivants (pool LRU) · pause des players hors page courante · `LockedOverlay` jamais monté sur un épisode débloqué · reprise de position sauvegardée toutes les 5 s et à `onPageChanged` · test swipe rapide 50 épisodes sans OOM (profileur mémoire Android Studio).

---

## 5. 🤖 ai-pipeline-engineer — Bibliothèque de prompts de génération

### 5.1 `pipeline/src/prompts/style-lock.ts` — verrou de style (cohérence visuelle globale)

```typescript
// Ce bloc est injecté mot pour mot dans TOUS les prompts image.
export const STYLE_LOCK = `
Style global (IMMUTABLE pour toute la série) :
- Rendu photo-cinématographique vertical 9:16, éclairage dramatique haut contraste,
  teintes chaudes orangées + ombres bleutées (blockbuster de romance Netflix).
- Grain léger, profondeur de champ courte, focus sur les visages.
- AUCUN texte, watermark, logo ou UI dans l'image.
- Les descriptions "appearance" des personnages (bible) doivent être reprises
  MOT POUR MOT dans chaque prompt où le personnage apparaît.
`;

export const NEGATIVE_PROMPT = `
text, watermark, logo, extra fingers, deformed hands, inconsistent face,
different hair color, different eye color, blurry, low quality, portrait landscape
`;
```

### 5.2 `pipeline/src/prompts/concept.ts` — concepteur du jour

```typescript
export const CONCEPT_SYSTEM = `Tu es le showrunner en chef d'un studio de micro-dramas verticaux
(TikTok-drama : épisodes de 2-3 min, public 18-40 ans, majoritairement féminin).
Tu maîtrises les tropes à très forte rétention : mariage secret, identité cachée de
milliardaire, revanche de l'héroïne sous-estimée, mafia protectrice, renaissance
(temporalité), faux contrat de couple qui devient réel, belle-mère hostile,
héritier déguisé en employé.

RÈGLES :
1. Le TITRE est une phrase accrocheuse à la 1re personne, genre :
   "Le père de ma meilleure amie m'a choisie" (ne jamais copier ces exemples).
2. Le "hook_first_3_episodes" doit créer une question irrésistible en < 90 secondes.
3. Le concept doit être DIFFÉRENCIÉ de la liste d'exclusion fournie.
4. Réponds UNIQUEMENT avec le JSON demandé, sans texte autour.`;

export function conceptUserPrompt(trendData: string, exclusionTitles: string[]): string {
  return `TENDANCES DU JOUR (requêtes populaires, genres sur-performants) :
${trendData}

TITRES DÉJÀ PUBLIÉS (interdits car trop proches) :
${exclusionTitles.slice(-200).join('\n')}

Génère le pitch d'UNE nouvelle série de 50 épisodes de 2-3 min. JSON exact :
{
  "title": "phrase-haccroche 1re personne",
  "emotional_tag": "2-4 mots (ex: Amour interdit)",
  "genre": "romance|revenge|mafia|fantasy|rebirth|thriller",
  "logline": "2 phrases",
  "hook_first_3_episodes": "le moment exact qui hook en épisode 1",
  "target_audience": "...",
  "differentiator": "ce qui distingue cette série des titres exclus"
}`;
}
```

### 5.3 `pipeline/src/prompts/bible.ts` — story bible (1 appel Opus)

```typescript
export const BIBLE_SYSTEM = `Tu es le scénariste en chef. Tu produis une STORY BIBLE stricte
qui garantit une cohérence parfaite des personnages et des arcs sur 50 épisodes.

EXIGENCES STRUCTURELLES :
- 3 à 5 personnages principaux MAX (économie de génération image/TTS).
- "appearance" : description physique MOT POUR MOT réutilisable comme seed de cohérence
  image (âge apparent, cheveux, yeux, silhouette, 2 marques distinctives).
- "voice" : description de voix pour TTS (timbre, débit, accent FR).
- Les beats : exactement 50, chaque épisode = un conflit + un cliffhanger brutal.
- Rythme type : acte 1 (ép. 1-10) mise en place, acte 2 (11-30) montée + retournements,
  acte 3 (31-45) crise, acte 4 (46-50) révélation finale + fin ouverte (saison 2).
- Chaque lieu est décrit de façon réutilisable comme seed de décor.

Réponds UNIQUEMENT avec le JSON demandé.`;

export function bibleUserPrompt(concept: unknown): string {
  return `SÉRIE À DÉVELOPPER : ${JSON.stringify(concept)}

Produis la bible :
{
  "characters": [
    {"id":"char_1","name":"...","age_appearance":30,"role":"lead/héroïne",
     "appearance":"[SEED IMMUTABLE] ...","wardrobe":"...","voice":"[TTS] ...",
     "personality":"...","secret":"...","arc":"..."}
  ],
  "locations": [{"id":"loc_1","name":"...","description":"[SEED IMMUTABLE] ..."}],
  "acts": [{"range":"1-10","title":"...","goal":"..."},{"range":"11-30",...},
           {"range":"31-45",...},{"range":"46-50",...}],
  "beats": [{"ep":1,"summary":"...","cliffhanger":"..."}],  // EXACTEMENT 50
  "season_finale_hook": "..."
}`;
}
```

### 5.4 `pipeline/src/prompts/episode.ts` — script par épisode

```typescript
export const EPISODE_SYSTEM = `Tu écris UN épisode de micro-drama vertical de 2 à 3 minutes.

FORMAT IMPOSÉ :
- 10 à 14 plans (shots). Durée cible totale : 120 à 180 secondes.
- ≈ 350 à 480 mots de dialogue TOTAL (rythme de 150-160 mots/minute).
- Chaque shot : 1 lieu, 1 à 3 répliques MAX.
- "image_prompt" : commence par le lieu seed, puis chaque personnage présent
  avec son "appearance" MOT POUR MOT, puis l'action. AJOUTE : "vertical 9:16, cinematic".
- "subtitle" : la synthèse du shot pour sourds/muets (FR).
- Le DERNIER shot contient OBLIGATOIREMENT le cliffhanger du beat.
- Continuité stricte avec l'extrait de fin de l'épisode précédent si fourni.
- Dialogue naturel parlé (pas de descriptions littéraires), interjections OK.

Réponds UNIQUEMENT avec le JSON demandé.`;

export function episodeUserPrompt(bible: {characters: any[]; locations: any[]},
                                 beat: {ep: number; summary: string; cliffhanger: string},
                                 prevEnding: string | null): string {
  return `PERSONNAGES (appearances = seeds immuables) :
${JSON.stringify(bible.characters)}
LIEUX :
${JSON.stringify(bible.locations)}

BEAT DE L'ÉPISODE ${beat.ep} : ${beat.summary}
CLIFFHANGER IMPOSÉ : ${beat.cliffhanger}
${prevEnding ? `\nFIN DE L'ÉPISODE ${beat.ep - 1} (continuité) : ...${prevEnding}` : '\nCeci est le PREMIER épisode : ouvre sur le hook du pitch.'}

JSON :
{
  "ep": ${beat.ep},
  "shots": [
    {"id":1,"location":"loc_1",
     "image_prompt":"[lieu seed]. [perso: appearance MOT POUR MOT]. [action dramatique précise]. vertical 9:16, cinematic",
     "dialogue":[{"char":"char_1","text":"..."}],
     "subtitle":"...","duration_sec":12}
  ],
  "duration_est_sec": 150,
  "ending_excerpt": "les 2 dernières répliques pour la continuité du prochain épisode"
}`;
}
```

### 5.5 `pipeline/src/prompts/quality.ts` — contrôle qualité automatique

```typescript
export const QUALITY_SYSTEM = `Tu es le contrôleur qualité narratif d'un studio de micro-dramas.
Tu notes un épisode de façon STRICTE et tu détectes : incohérence avec la bible,
cliffhanger faible, dialogue artificiel, durée hors cible, contenu interdit
(marque concurrente, contenu sensible, mineurs sexualisés, violence graphique).`;

export function qualityUserPrompt(bible: unknown, script: unknown): string {
  return `BIBLE (extrait) : ${JSON.stringify(bible).slice(0, 4000)}

SCRIPT À NOTER : ${JSON.stringify(script)}

Réponds :
{"score":0,"inconsistencies":["..."],"banned_content":[],"cliffhanger_strength":0,
 "suggested_fix":"une phrase de correction si score < 7"}`;
}
// Boucle : si score < 7 -> régénérer l'épisode en injectant "suggested_fix"
// dans le prompt épisode (max 2 tentatives, sinon flag manuel showrunner).
```

### 5.6 Validation Zod `pipeline/src/schemas.ts`

```typescript
import { z } from 'zod';

export const ConceptSchema = z.object({
  title: z.string().min(10).max(80),
  emotional_tag: z.string().min(2).max(40),
  genre: z.enum(['romance', 'revenge', 'mafia', 'fantasy', 'rebirth', 'thriller']),
  logline: z.string().min(20),
  hook_first_3_episodes: z.string().min(20),
  target_audience: z.string(),
  differentiator: z.string(),
});

export const BibleSchema = z.object({
  characters: z.array(z.object({
    id: z.string().regex(/^char_\d+$/),
    name: z.string(),
    appearance: z.string().min(60),        // le seed doit être riche
    voice: z.string().min(20),
    wardrobe: z.string().optional(),
  })).min(2).max(5),
  locations: z.array(z.object({
    id: z.string().regex(/^loc_\d+$/),
    description: z.string().min(40),
  })).min(1),
  beats: z.array(z.object({
    ep: z.number().int(),
    summary: z.string(),
    cliffhanger: z.string(),
  })).length(50),                            // EXACTEMENT 50
});

export const EpisodeSchema = z.object({
  ep: z.number().int().min(1).max(50),
  duration_est_sec: z.number().int().min(110).max(185),
  shots: z.array(z.object({
    id: z.number().int(),
    location: z.string(),
    image_prompt: z.string().min(80),
    dialogue: z.array(z.object({ char: z.string(), text: z.string().max(220) })),
    subtitle: z.string(),
    duration_sec: z.number().int().min(4).max(30),
  })).min(10).max(14),
  ending_excerpt: z.string(),
});

// JSON parse robuste : gérer les fences ```json et les virgules traînantes
export function parseClaudedJson<T>(raw: string, schema: z.ZodType<T>): T {
  const cleaned = raw.replace(/^```(json)?/m, '').replace(/```\s*$/m, '')
                     .replace(/,\s*([}\]])/g, '$1');
  return schema.parse(JSON.parse(cleaned.trim()));
}
```

**Boucle de régénération (résumé exécutif) :**

```text
pour chaque épisode:
  script = claude(EPISODE_SYSTEM, episodeUserPrompt(...))
  épisode = parseClaudedJson(script, EpisodeSchema)      // zod rejette tout JSON invalide
  qualité = claude(QUALITY_SYSTEM, qualityUserPrompt(...))
  si qualité.score < 7 et tentative < 2:
    réinjecter "suggested_fix" -> régénérer (nouvelle tentative)
  si toujours < 7:
    marquer REVIEW_MANUAL (le content-showrunner tranche en revue de fin)
```

---

## 6. ✅ Definition of Done globale du Sprint 1

| Critère | Agent responsable |
|---|---|
| `npm run typecheck` vert (backend + pipeline + shared) | devops-engineer |
| `./gradlew assembleDebug` + lint verts | android-developer |
| Home : 4 onglets opérationnels, catégories déroulantes, badges FR | android-developer |
| Lecteur : swipe 50 épisodes sans OOM, unlock → reprise immédiate | android-developer |
| `prisma migrate` + 3 tests e2e wallet verts (dont double-dépense concurrent) | backend-developer |
| Zod valide bible (50 beats) + épisodes (10-14 shots) sur 3 générations test | ai-pipeline-engineer |
| CI GitHub verte sur la branche `feat/sprint-1` | devops-engineer |
| Revue des PR par qa-engineer avant merge sur main | qa-engineer |

**Sprint 2 (suite logique) :** écran Détail + grille d'épisodes (android), API streaming signée `stream.service.ts` (backend), rendu FFmpeg complet + TTS par personnage (pipeline), parrainage (backend+android).