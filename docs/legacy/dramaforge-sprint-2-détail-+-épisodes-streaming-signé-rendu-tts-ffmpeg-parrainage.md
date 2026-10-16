# 🚀 DramaForge — Sprint 2 : Implémentation multi-agents (suite)

> Périmètre : écran Détail + grille d'épisodes (android-developer) · service de streaming signé (backend-developer) · rendu complet TTS + FFmpeg + HLS (ai-pipeline-engineer) · parrainage de bout en bout (backend + android).

**Dispatch dans Claude Code :**

```text
1. backend-developer    -> stream/ (URL signée HMAC) + referral/  (sections 2 et 4.1-4.3)
2. android-developer    -> feature/detail/ (section 1)
3. ai-pipeline-engineer -> pipeline/src/tts + render (section 3)
4. android-developer    -> feature/referral/ (section 4.4)
5. qa-engineer          -> revue finale : e2e streaming, referral, assembleDebug, dry-run pipeline
```

---

## 1. 📱 android-developer — Écran Détail + grille d'épisodes

### 1.1 `domain/model/EpisodeGrid.kt` — état calculé par épisode

```kotlin
package com.dramaforge.app.domain.model

data class DramaDetail(
    val id: String,
    val title: String,
    val description: String,
    val emotionalTag: String?,
    val coverUrl: String,
    val rating: Float,
    val views: Long,
    val episodeCount: Int,
    val status: String,                 // ONGOING | COMPLETED
    val isExclusive: Boolean,
)

enum class EpisodeState { WATCHED, CURRENT, UNLOCKED, AFFORDABLE, LOCKED }

data class EpisodeGridItem(
    val id: String,
    val number: Int,
    val priceCoins: Int,
    val isFree: Boolean,
    val state: EpisodeState,
    val isLatest: Boolean,
)
```

### 1.2 `feature/detail/DetailViewModel.kt`

```kotlin
package com.dramaforge.app.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dramaforge.app.core.network.ApiService
import com.dramaforge.app.domain.model.*
import com.dramaforge.app.domain.repository.LibraryRepository
import com.dramaforge.app.domain.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val isLoading: Boolean = true,
    val drama: DramaDetail? = null,
    val episodes: List<EpisodeGridItem> = emptyList(),
    val walletCoins: Int = 0,
    val vipActive: Boolean = false,
    val resumeEpisodeNumber: Int? = null,   // épisode à reprendre (progression)
    val isFavorite: Boolean = false,
    val coinsToFinish: Int = 0,              // coût pour débloquer toute la série
    val error: String? = null,
    val pendingUnlockEpisodeId: String? = null,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val api: ApiService,
    private val walletRepository: WalletRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    fun load(dramaId: String) = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val detail = api.getDrama(dramaId).toDomain()
            val episodes = api.getEpisodes(dramaId)
            val wallet = api.getWallet()
            val libraryInfo = api.getDramaLibraryInfo(dramaId)  // progression + favori

            val items = episodes.map { ep ->
                val unlocked = ep.free || wallet.unlockedEpisodeIds.contains(ep.id) || wallet.vipActive
                val watched = libraryInfo.watchedEpisodeIds.contains(ep.id)
                val current = libraryInfo.currentEpisodeId == ep.id
                val state = when {
                    watched -> EpisodeState.WATCHED
                    current -> EpisodeState.CURRENT
                    unlocked -> EpisodeState.UNLOCKED
                    wallet.totalCoins >= ep.priceCoins -> EpisodeState.AFFORDABLE
                    else -> EpisodeState.LOCKED
                }
                EpisodeGridItem(ep.id, ep.number, ep.priceCoins, ep.free, state,
                                isLatest = ep.number == episodes.last().number)
            }
            _uiState.update {
                DetailUiState(
                    isLoading = false, drama = detail, episodes = items,
                    walletCoins = wallet.totalCoins, vipActive = wallet.vipActive,
                    resumeEpisodeNumber = libraryInfo.resumeEpisodeNumber,
                    isFavorite = libraryInfo.isFavorite,
                    coinsToFinish = items.filter { it.state == EpisodeState.LOCKED }
                                         .sumOf { it.priceCoins },
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message ?: "Erreur") }
        }
    }

    fun unlockEpisode(episodeId: String) = viewModelScope.launch {
        _uiState.update { it.copy(pendingUnlockEpisodeId = episodeId) }
        walletRepository.unlockEpisode(episodeId)
            .onSuccess { res ->
                _uiState.update { s -> s.copy(
                    pendingUnlockEpisodeId = null,
                    walletCoins = res.totalCoins,
                    episodes = s.episodes.map { e ->
                        if (e.id == episodeId) e.copy(state = EpisodeState.UNLOCKED) else e },
                ) }
            }
            .onFailure { e ->
                _uiState.update { s -> s.copy(pendingUnlockEpisodeId = null,
                    error = "Déblocage impossible : ${e.message}") }
            }
    }

    fun toggleFavorite(dramaId: String) = viewModelScope.launch {
        val newState = !_uiState.value.isFavorite
        if (newState) library.addFavorite(dramaId) else library.removeFavorite(dramaId)
        _uiState.update { it.copy(isFavorite = newState) }
    }

    /** Débloquer en lot tous les épisodes verrouillés (transactionnel côté backend). */
    fun unlockAllRemaining(dramaId: String) = viewModelScope.launch {
        runCatching { api.unlockAll(UnlockAllRequest(dramaId)) }
            .onSuccess { res -> _uiState.update { s -> s.copy(
                walletCoins = res.totalCoins,
                episodes = s.episodes.map { e ->
                    if (e.state == EpisodeState.LOCKED || e.state == EpisodeState.AFFORDABLE)
                        e.copy(state = EpisodeState.UNLOCKED) else e }) } }
            .onFailure { e -> _uiState.update { s -> s.copy(error = e.message) } }
    }
}
```

### 1.3 `feature/detail/DetailScreen.kt` — écran complet

```kotlin
package com.dramaforge.app.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dramaforge.app.core.design.LoadingState
import com.dramaforge.app.domain.model.EpisodeState

@Composable
fun DetailScreen(
    dramaId: String,
    onBack: () -> Unit,
    onPlay: (dramaId: String, episodeId: String) -> Unit,
    onGoWallet: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(dramaId) { viewModel.load(dramaId) }

    when {
        state.isLoading -> LoadingState()
        state.error != null && state.drama == null ->
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.error!!); Button(onClick = { viewModel.load(dramaId) }) { Text("Réessayer") }
            }
        else -> {
            val drama = state.drama!!
            val resume = state.episodes.firstOrNull { it.state == EpisodeState.CURRENT }
                ?: state.episodes.firstOrNull { it.state != EpisodeState.WATCHED }
                ?: state.episodes.first()

            Box(Modifier.fillMaxSize()) {
                // Grille : la 1re "ligne" est un header pleine largeur (span 2)
                LazyVerticalGrid(columns = GridCells.Fixed(2)) {
                    item(span = { GridItemSpan(2) }) {
                        DetailHeader(drama = drama, isFavorite = state.isFavorite,
                            onBack = onBack, onToggleFavorite = { viewModel.toggleFavorite(dramaId) })
                    }
                    item(span = { GridItemSpan(2) }) {
                        CtaBar(
                            resumeLabel = if (resume.state == EpisodeState.CURRENT)
                                "Reprendre ép. ${resume.number}" else "Lire épisode 1",
                            coinsToFinish = state.coinsToFinish,
                            walletCoins = state.walletCoins,
                            onPlay = { onPlay(dramaId, resume.id) },
                            onUnlockAll = { viewModel.unlockAllRemaining(dramaId) },
                            onGoWallet = onGoWallet,
                        )
                    }
                    item(span = { GridItemSpan(2) }) {
                        Text("Épisodes (${drama.episodeCount})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    items(state.episodes, key = { it.id }) { episode ->
                        EpisodeChip(
                            item = episode,
                            pending = state.pendingUnlockEpisodeId == episode.id,
                            onClick = {
                                when (episode.state) {
                                    EpisodeState.WATCHED, EpisodeState.CURRENT,
                                    EpisodeState.UNLOCKED -> onPlay(dramaId, episode.id)
                                    EpisodeState.AFFORDABLE -> viewModel.unlockEpisode(episode.id)
                                    EpisodeState.LOCKED -> onGoWallet()
                                }
                            })
                    }
                }
                // Snackbars d'erreur locales (déverrouillage, réseau)
                state.error?.let { msg ->
                    Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        action = { TextButton(onClick = onGoWallet) { Text("Boutique") } }) {
                        Text(msg)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(drama: DramaDetail, isFavorite: Boolean,
                         onBack: () -> Unit, onToggleFavorite: () -> Unit) {
    Box {
        AsyncImage(model = drama.coverUrl, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(260.dp))
        Box(Modifier.fillMaxWidth().height(260.dp)
            .background(Brush.verticalGradient(listOf(Color(0x66000000), Color(0xFF0E0B16)))))
        IconButton(onClick = onBack, Modifier.statusBarsPadding()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, tint = Color.White)
        }
        IconButton(onClick = onToggleFavorite, Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
            Icon(if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                tint = if (isFavorite) Color(0xFFFF5470) else Color.White)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            if (drama.isExclusive) Text("EXCLUSIVE ·", color = Color(0xFFB39DFF),
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            Text(drama.title, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("★ ${"%.1f".format(drama.rating)}", color = Color(0xFFFFD54F))
                Text("${drama.views / 1_000_000.0}M vues", color = Color(0xB3FFFFFF))
                Text(if (drama.status == "ONGOING") "En cours" else "Terminé",
                    color = Color(0xFF7BE3A3))
            }
        }
    }
}

@Composable
private fun CtaBar(resumeLabel: String, coinsToFinish: Int, walletCoins: Int,
                   onPlay: () -> Unit, onUnlockAll: () -> Unit, onGoWallet: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Button(onClick = onPlay, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("▶  $resumeLabel", fontWeight = FontWeight.Bold)
        }
        if (coinsToFinish > 0) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onUnlockAll, modifier = Modifier.fillMaxWidth(),
                enabled = walletCoins >= coinsToFinish) {
                Text("Tout débloquer · 🪙 $coinsToFinish" +
                    (if (walletCoins < coinsToFinish) " (insuffisant)" else ""))
            }
        }
    }
}

@Composable
private fun EpisodeChip(item: EpisodeGridItem, pending: Boolean, onClick: () -> Unit) {
    val bg = when (item.state) {
        EpisodeState.WATCHED -> Color(0xFF1E2430)
        EpisodeState.CURRENT -> Color(0xFF6C2BD9)
        EpisodeState.UNLOCKED -> Color(0xFF263042)
        EpisodeState.AFFORDABLE -> Color(0xFF334059)
        EpisodeState.LOCKED -> Color(0xFF141822)
    }
    Card(onClick = onClick, modifier = Modifier.padding(4.dp), colors = CardDefaults.cardColors(
        containerColor = bg), shape = MaterialTheme.shapes.small) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${item.number}", fontWeight = FontWeight.Bold,
                    color = if (item.state == EpisodeState.LOCKED) Color(0x80FFFFFF) else Color.White)
                when {
                    pending -> CircularProgressIndicator(Modifier.size(14.dp).padding(top = 2.dp),
                        strokeWidth = 2.dp, color = Color(0xFFFFD54F))
                    item.state == EpisodeState.WATCHED -> Text("✓", color = Color(0xFF7BE3A3))
                    item.state == EpisodeState.CURRENT -> Text("▶", color = Color.White)
                    item.state == EpisodeState.LOCKED || item.state == EpisodeState.AFFORDABLE ->
                        if (item.isFree) Text("GRATUIT", color = Color(0xFF7BE3A3),
                            style = MaterialTheme.typography.labelSmall)
                        else Text("🪙 ${item.priceCoins}", color = Color(0xFFFFD54F),
                            style = MaterialTheme.typography.labelSmall)
                    else -> {}
                }
            }
        }
    }
}
```

**DoD android (Détail) :** `assembleDebug` vert · reprise = épisode `CURRENT` sinon 1er non-vu · `pendingUnlockEpisodeId` affiche un spinner sur la seule case cliquée · "Tout débloquer" désactivé si solde insuffisant · test `DetailViewModelTest` (mapping des 5 états d'épisode).

---

## 2. 🗄️ backend-developer — Service de streaming signé

### 2.1 Principe

L'app appelle `GET /stream/:episodeId/url` **après** vérification d'accès ; le backend répond avec une URL CDN **signée HMAC à TTL court (5 min)**. Le CDN (Cloudflare) ne sert les segments HLS qu'avec un token valide — impossible de partager un lien permanent.

### 2.2 `backend/src/stream/stream.service.ts`

```typescript
import { ForbiddenException, Injectable, NotFoundException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { createHmac, timingSafeEqual } from 'crypto';
import { PrismaService } from '../prisma/prisma.service';

const TOKEN_TTL_SEC = 5 * 60;

@Injectable()
export class StreamService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly config: ConfigService,
  ) {}

  private get secret(): string {
    return this.config.getOrThrow<string>('STREAM_SIGNING_SECRET');
  }

  private get cdnBase(): string {
    return this.config.getOrThrow<string>('CDN_BASE_URL');
  }

  /** Accès accordé si : épisode gratuit, OU débloqué, OU VIP actif. */
  private async checkAccess(userId: string, episodeId: string) {
    const episode = await this.prisma.episode.findUnique({ where: { id: episodeId } });
    if (!episode) throw new NotFoundException('Épisode introuvable');
    if (episode.free) return episode;

    const unlock = await this.prisma.unlock.findUnique({
      where: { userId_episodeId: { userId, episodeId } },
    });
    if (unlock) return episode;

    const vip = await this.prisma.subscription.findFirst({
      where: { userId, status: 'active', expiresAt: { gt: new Date() } },
    });
    if (vip) return episode;

    throw new ForbiddenException('Épisode verrouillé');
  }

  async getSignedUrl(userId: string, episodeId: string) {
    const episode = await this.checkAccess(userId, episodeId);
    const exp = Math.floor(Date.now() / 1000) + TOKEN_TTL_SEC;
    // Le token lie l'épisode au timestamp : un même token ne vaut que 5 min.
    const token = this.sign(episodeId, exp);
    return {
      hls: `${this.cdnBase}/hls/${episodeId}/master.m3u8?exp=${exp}&token=${token}`,
      vtt: episode.vttUrl ? `${episode.vttUrl}?exp=${exp}&token=${this.sign(episodeId + ':vtt', exp)}` : null,
      durationSec: episode.durationSec,
      expiresAt: new Date(exp * 1000).toISOString(),
    };
  }

  private sign(payload: string, exp: number): string {
    return createHmac('sha256', this.secret).update(`${payload}.${exp}`).digest('base64url');
  }

  /** Utilisé par le middleware/worker CDN pour valider chaque requête de segment. */
  verify(episodeId: string, exp: string, token: string, kind: 'hls' | 'vtt' = 'hls'): boolean {
    if (Number(exp) * 1000 < Date.now()) return false;         // expiré
    const expected = this.sign(kind === 'vtt' ? episodeId + ':vtt' : episodeId, Number(exp));
    const a = Buffer.from(expected);
    const b = Buffer.from(token);
    return a.length === b.length && timingSafeEqual(a, b);     // comparaison constante
  }
}
```

### 2.3 `backend/src/stream/stream.controller.ts`

```typescript
import { Controller, Get, Param, Req, UseGuards } from '@nestjs/common';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { StreamService } from './stream.service';

@Controller('stream')
@UseGuards(JwtAuthGuard)
export class StreamController {
  constructor(private readonly stream: StreamService) {}

  /** L'app appelle cette route à CHAQUE entrée dans le lecteur (token court). */
  @Get(':episodeId/url')
  async getUrl(@Req() req: any, @Param('episodeId') episodeId: string) {
    return this.stream.getSignedUrl(req.user.id, episodeId);
  }
}
```

### 2.4 Test e2e `backend/test/stream.e2e-spec.ts` (extraits)

```typescript
it('retourne une URL signée pour un épisode débloqué', async () => {
  const res = await request(app.getHttpServer())
    .get(`/stream/${unlockedEpisodeId}/url`).set('Authorization', `Bearer ${token}`)
    .expect(200);
  expect(res.body.hls).toMatch(/exp=\d+&token=[\w-]+$/);
  expect(new Date(res.body.expiresAt).getTime()).toBeGreaterThan(Date.now());
});

it('refuse (403) un épisode verrouillé sans VIP', async () => {
  await request(app.getHttpServer())
    .get(`/stream/${lockedEpisodeId}/url`).set('Authorization', `Bearer ${token}`)
    .expect(403);
});

it('sert un épisode payant à un VIP actif', async () => {
  await giveVip(prisma, userId, 7); // Subscription active 7 jours
  await request(app.getHttpServer())
    .get(`/stream/${lockedEpisodeId}/url`).set('Authorization', `Bearer ${token}`)
    .expect(200);
});
```

**DoD backend (stream) :** TTL 5 min · `timingSafeEqual` · 403 sur épisode verrouillé · 200 pour free/unlocked/VIP · test e2e vert.

---

## 3. 🤖 ai-pipeline-engineer — Rendu complet : TTS par personnage + FFmpeg + HLS

### 3.1 Vue d'ensemble du rendu d'un épisode

```text
script JSON (50x)
  └─> 1. tts.ts      : voix par personnage (voice_id bible) -> mp3 par réplique
                       + timings via ffprobe -> timeline
  └─> 2. subtitles.ts : timeline -> fichier .ass (stylé) + .vtt (app)
  └─> 3. ffmpeg.ts    : frames PNG -> zoompan Ken Burns par shot,
                       concat découpé sur la timeline, mix musique -18dB
  └─> 4. hls.ts       : mp4 -> HLS VOD (segments 4s) + rendu 480p/720p/1080p
  └─> 5. upload.ts    : R2 (multiadmin) + CDN purge + retour des URL finales
```

### 3.2 `pipeline/src/tts.ts` — voix par personnage + timeline

```typescript
import { execFile } from 'child_process';
import { promisify } from 'util';
import fs from 'fs/promises';
import path from 'path';
import { EpisodeScript, Bible } from './schemas';

const run = promisify(execFile);

// ---- Abstraction TTS (provider-agnostic : ElevenLabs, OpenAI, Azure...) ----
export interface TtsProvider {
  /** Renvoie le chemin du mp3 généré et sa durée en secondes. */
  synth(text: string, voiceId: string, outPath: string,
        opts?: { emotion?: string; speed?: number }): Promise<{ file: string; durationSec: number }>;
}

export class ElevenLabsTts implements TtsProvider {
  constructor(private apiKey: string, private baseUrl = 'https://api.elevenlabs.io/v1') {}

  async synth(text: string, voiceId: string, outPath: string,
              opts?: { emotion?: string; speed?: number }) {
    const res = await fetch(`${this.baseUrl}/text-to-speech/${voiceId}`, {
      method: 'POST',
      headers: { 'xi-api-key': this.apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        text,
        model_id: 'eleven_multilingual_v2',   // FR natif de qualité
        voice_settings: {
          stability: 0.45, similarity_boost: 0.8,
          style: 0.4, speed: opts?.speed ?? 1.0,
        },
      }),
    });
    if (!res.ok) throw new Error(`TTS ${res.status}: ${await res.text()}`);
    await fs.writeFile(outPath, Buffer.from(await res.arrayBuffer()));
    const { durationSec } = await probeDuration(outPath);
    return { file: outPath, durationSec };
  }
}

async function probeDuration(file: string) {
  const { stdout } = await run('ffprobe', ['-v', 'error', '-show_entries',
    'format=duration', '-of', 'default=noprint_wrappers=1:nokey=1', file]);
  return { durationSec: parseFloat(stdout.trim()) };
}

export interface TimelineLine {
  charId: string; startSec: number; endSec: number; text: string; file: string;
}

/**
 * Synthétise TOUTES les répliques d'un épisode, une voix par personnage,
 * et calcule la timeline (les répliques d'un même shot s'enchaînent,
 * pause 350 ms entre répliques, silences respects de duration_sec du shot).
 */
export async function synthEpisode(
  bible: Bible, script: EpisodeScript, tts: TtsProvider, workDir: string,
): Promise<{ timeline: TimelineLine[]; totalAudioSec: number }> {
  await fs.mkdir(workDir, { recursive: true });
  const voiceOf = new Map(bible.characters.map(c => [c.id, c.voiceId])); // voiceId stable (bible)
  const timeline: TimelineLine[] = [];
  let cursor = 0.0;

  for (const shot of script.shots) {
    for (const line of shot.dialogue) {
      const voiceId = voiceOf.get(line.char);
      if (!voiceId) throw new Error(`Personnage inconnu : ${line.char}`);
      const file = path.join(workDir, `l_${shot.id}_${timeline.length}.mp3`);
      const { durationSec } = await tts.synth(line.text, voiceId, file, {
        speed: 1.0,
      });
      timeline.push({ charId: line.char, startSec: cursor, endSec: cursor + durationSec,
                      text: line.text, file });
      cursor += durationSec + 0.35;   // respiration entre répliques
    }
  }
  return { timeline, totalAudioSec: cursor };
}
```

### 3.3 `pipeline/src/subtitles.ts` — ASS stylé (dans la vidéo) + VTT (app)

```typescript
import fs from 'fs/promises';
import { TimelineLine } from './tts';

const assTime = (t: number) => {
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60),
        s = Math.floor(t % 60), cs = Math.round((t - Math.floor(t)) * 100);
  return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}.${String(cs).padStart(2, '0')}`;
};

const vttTime = (t: number) => {
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60),
        s = Math.floor(t % 60), ms = Math.round((t - Math.floor(t)) * 1000);
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}.${String(ms).padStart(3, '0')}`;
};

/** Sous-titres incrustés (stylé jaune, contour noir, bas-centre) façon DramaWave. */
export async function writeAss(timeline: TimelineLine[], out: string) {
  const header = `[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, OutlineColour, BackColour, Bold, Outline, Shadow, Alignment, MarginV
Style: Default,Arial,64,&H00FFFFFF,&H00000000,&H80000000,1,4,1,2,140

[Events]
Format: Layer, Start, End, Style, Text`;

  const events = timeline.map(l =>
    `Dialogue: 0,${assTime(l.startSec)},${assTime(l.endSec)},Default,${l.text.replace(/\n/g, '\\N')}`);

  await fs.writeFile(out, `${header}\n${events.join('\n')}\n`);
}

/** Sous-titres .vtt servis dans l'app (activables/désactivables par l'utilisateur). */
export async function writeVtt(timeline: TimelineLine[], out: string) {
  const cues = timeline.map(l =>
    `${vttTime(l.startSec)} --> ${vttTime(l.endSec)}\n${l.text}`);
  await fs.writeFile(out, `WEBVTT\n\n${cues.join('\n\n')}\n`);
}
```

### 3.4 `pipeline/src/render.ts` — FFmpeg complet

```typescript
import { execFile } from 'child_process';
import { promisify } from 'util';
import fs from 'fs/promises';
import path from 'path';
import { EpisodeScript } from './schemas';
import { TimelineLine } from './tts';

const run = promisify(execFile);
const ff = async (args: string[]) => { const { stderr } = await run('ffmpeg', ['-y', ...args]); return stderr; };

/**
 * Rend UN épisode complet :
 * 1) un mp4 par shot (Ken Burns zoompan sur la frame + audio du shot)
 * 2) concat de tous les shots
 * 3) mix musique de fond en sidechain (le dialogue garde la priorité)
 * 4) incrustation des sous-titres .ass
 */
export async function renderEpisode(
  script: EpisodeScript,
  framesDir: string,          // frames PNG 1080x1920 par shot : `${shot.id}.png`
  timeline: TimelineLine[],
  workDir: string,
  assets: { music: string; assSubs: string },
): Promise<string> {
  await fs.mkdir(workDir, { recursive: true });
  const shotFiles: string[] = [];

  // Le shot n est aligné sur la timeline : ses répliques définissent sa durée.
  let cursor = 0.0;
  for (const shot of script.shots) {
    const shotLines = timeline.filter(l => l.file.includes(`l_${shot.id}_`));
    const shotEnd = shotLines.length ? shotLines.at(-1)!.endSec + 0.6 : cursor + shot.duration_sec;
    const dur = Math.max(4, shotEnd - cursor);
    const frame = path.join(framesDir, `${shot.id}.png`);
    const out = path.join(workDir, `shot_${String(shot.id).padStart(3, '0')}.mp4`);
    shotFiles.push(out);

    // Audio du shot : concat des répliques de la timeline avec silences calés.
    const filter = shotLines.length
      ? shotLines.map((l, i) =>
          `[${i + 1}:a]adelay=${Math.round((l.startSec - cursor) * 1000)}|${Math.round((l.startSec - cursor) * 1000)}[a${i}]`
        ).join(';') + ';' +
        shotLines.map((_, i) => `[a${i}]`).join('') +
        `amix=inputs=${shotLines.length}:normalize=0,apad=pad_dur=2[aout]`
      : null;

    await ff(filter
      ? ['-loop', '1', '-t', String(dur), '-i', frame,
         ...shotLines.flatMap(l => ['-i', l.file]),
         '-filter_complex',
         `[0:v]scale=1080:1920,zoompan=z='1+0.0006*on':d=25*${dur}:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1920,fps=25[v];${filter}`,
         '-map', '[v]', '-map', '[aout]', '-t', String(dur),
         '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '21',
         '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '160k', out]
      : ['-loop', '1', '-t', String(dur), '-i', frame,
         '-f', 'lavfi', '-i', 'anullsrc=r=44100:cl=stereo',
         '-filter_complex',
         `[0:v]scale=1080:1920,zoompan=z='1+0.0006*on':d=25*${dur}:s=1080x1920,fps=25[v]`,
         '-map', '[v]', '-map', '1:a', '-t', String(dur),
         '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '21',
         '-pix_fmt', 'yuv420p', '-c:a', 'aac', out]);

    cursor = shotEnd;
  }

  // ---- Concat des shots (safe pattern: concat demuxer, mêmes codecs/params) ----
  const concatList = path.join(workDir, 'concat.txt');
  await fs.writeFile(concatList, shotFiles.map(f => `file '${f}'`).join('\n'));
  const silent = path.join(workDir, 'episode_silent.mp4');
  await ff(['-f', 'concat', '-safe', '0', '-i', concatList, '-c', 'copy', silent]);

  // ---- Mix final : musique de fond duckée sous le dialogue (sidechain) ----
  const finalMp4 = path.join(workDir, 'episode.mp4');
  await ff(['-i', silent,
            '-stream_loop', '-1', '-i', assets.music,
            '-filter_complex',
            '[1:a]volume=0.18[duck];[0:a][duck]amix=inputs=2:duration=first[a]',
            '-vf', `ass=${assets.assSubs}`,
            '-map', '0:v', '-map', '[a]',
            '-c:v', 'libx264', '-preset', 'medium', '-crf', '20',
            '-c:a', 'aac', '-b:a', '160k', finalMp4]);

  return finalMp4;
}
```

### 3.5 `pipeline/src/hls.ts` — packaging multi-qualité

```typescript
import { execFile } from 'child_process';
import { promisify } from 'util';
const run = promisify(execFile);

/** HLS VOD : 3 renditions verticaux, master playlist, segments 4 s. */
export async function packageHls(mp4: string, outDir: string) {
  await run('ffmpeg', ['-y', '-i', mp4,
    // 1080p vertical
    '-vf', 'scale=1080:1920', '-c:v', 'libx264', '-crf', '22', '-preset', 'fast',
    '-c:a', 'aac', '-b:a', '160k', '-hls_time', '4', '-hls_playlist_type', 'vod',
    '-hls_segment_filename', `${outDir}/1080/seg_%04d.ts`, `${outDir}/1080/index.m3u8`,
    // 720p vertical (qualité "économie de données")
    '-vf', 'scale=720:1280', '-c:v', 'libx264', '-crf', '24', '-preset', 'fast',
    '-c:a', 'aac', '-b:a', '128k', '-hls_time', '4', '-hls_playlist_type', 'vod',
    '-hls_segment_filename', `${outDir}/720/seg_%04d.ts`, `${outDir}/720/index.m3u8`,
    // 480p vertical (lecture lente réseau)
    '-vf', 'scale=480:854', '-c:v', 'libx264', '-crf', '27', '-preset', 'fast',
    '-c:a', 'aac', '-b:a', '96k', '-hls_time', '4', '-hls_playlist_type', 'vod',
    '-hls_segment_filename', `${outDir}/480/seg_%04d.ts`, `${outDir}/480/index.m3u8`,
  ]);
  await writeMasterPlaylist(outDir);
}

async function writeMasterPlaylist(outDir: string) {
  const { writeFile } = await import('fs/promises');
  await writeFile(`${outDir}/master.m3u8`, `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1080x1920,CODECS="avc1.640028,mp4a.40.2"
1080/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=720x1280,CODECS="avc1.640028,mp4a.40.2"
720/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=480x854,CODECS="avc1.64001f,mp4a.40.2"
480/index.m3u8
`);
}
```

### 3.6 `pipeline/src/render-episode-orchestrator.ts` — bout en bout

```typescript
export async function produceEpisode(bible: Bible, script: EpisodeScript, ctx: RenderCtx) {
  const work = path.join(ctx.root, `ep_${script.ep}`);

  // 1) Images par shot ( STYLE_LOCK + appearance immuable de la bible )
  const frames = await generateFrames(bible, script, path.join(work, 'frames'));

  // 2) TTS par personnage + timeline
  const { timeline } = await synthEpisode(bible, script, ctx.tts, path.join(work, 'audio'));

  // 3) Sous-titres (incrustés + externes)
  const ass = path.join(work, 'subs.ass');
  const vtt = path.join(work, 'subs.vtt');
  await writeAss(timeline, ass);
  await writeVtt(timeline, vtt);

  // 4) Montage + musique (musique choisie par genre, en bibliothèque locale licenciée)
  const mp4 = await renderEpisode(script, frames, timeline, path.join(work, 'shots'),
    { music: ctx.musicForGenre(bible.genre), assSubs: ass });

  // 5) HLS + upload
  const hlsDir = path.join(work, 'hls');
  await packageHls(mp4, hlsDir);
  const urls = await ctx.uploader.uploadEpisode(hlsDir, vtt, `dramas/${ctx.dramaId}/ep_${script.ep}`);

  return { episodeNumber: script.ep, hlsUrl: urls.hls, vttUrl: urls.vtt,
           durationSec: Math.round(timeline.reduce((a, l) => Math.max(a, l.endSec), 0)) };
}
```

**DoD pipeline (rendu) :** dry-run sur 2 épisodes · voix identiques entre épisodes pour un même personnage (voice_id bible) · sous-titres VTT alignés à ±200 ms · master.m3u8 jouable dans `ffplay` et ExoPlayer · durée finale entre 2:00 et 3:15 · musique duckée sous le dialogue.

---

## 4. 🤝 Parrainage de bout en bout (backend + android)

### 4.1 Migration Prisma (à appliquer)

```prisma
// Ajout dans le model User :
referralCode String?  @unique     // code court lisible, ex: "ATC7X2"
referrerId  String?               // rempli à la création si code fourni

// Le model Referral existant (Sprint 1) reste la trace des récompenses.
// Migration : npx prisma migrate dev --name add_referral_code
```

### 4.2 `backend/src/referral/referral.service.ts`

```typescript
import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { randomBytes } from 'crypto';

export const REFERRAL_RATE = 0.10;   // 10 % des pièces rechargées par l'ami

@Injectable()
export class ReferralService {
  constructor(private readonly prisma: PrismaService) {}

  /** Code court, lisible, garanti unique. */
  private newCode(): string {
    return randomBytes(4).toString('base64url').replace(/[-_]/g, '').toUpperCase().slice(0, 6);
  }

  async getOrCreateCode(userId: string): Promise<string> {
    const user = await this.prisma.user.findUniqueOrThrow({ where: { id: userId } });
    if (user.referralCode) return user.referralCode;
    for (let i = 0; i < 5; i++) {
      try {
        const updated = await this.prisma.user.update({
          where: { id: userId }, data: { referralCode: this.newCode() },
        });
        return updated.referralCode!;
      } catch { /* collision : retry */ }
    }
    throw new BadRequestException('Génération de code impossible');
  }

  /** L'ami saisit le code du parrain UNE SEULE FOIS (à l'inscription ou plus tard). */
  async attachReferrer(friendId: string, code: string) {
    const referrer = await this.prisma.user.findUnique({ where: { referralCode: code.toUpperCase() } });
    if (!referrer) throw new NotFoundException('Code invalide');
    if (referrer.id === friendId) throw new BadRequestException('Auto-parrainage interdit');

    await this.prisma.$transaction(async (tx) => {
      const friend = await tx.user.findUniqueOrThrow({ where: { id: friendId } });
      if (friend.referrerId) throw new BadRequestException('Déjà parrainé');
      await tx.user.update({ where: { id: friendId }, data: { referrerId: referrer.id } });
    });
    return { ok: true, referrerName: referrer.displayName ?? 'votre ami' };
  }

  /**
   * Appelé par BillingService APRÈS validation serveur d'un achat de pièces :
   * le parrain reçoit 10 % du montant en pièces BONUS (consommées en premier).
   */
  async onFriendRecharge(friendId: string, coinsPurchased: number, purchaseToken: string) {
    const friend = await this.prisma.user.findUniqueOrThrow({ where: { id: friendId } });
    if (!friend.referrerId) return { ok: false, reason: 'NO_REFERRER' };

    const reward = Math.floor(coinsPurchased * REFERRAL_RATE);
    await this.prisma.$transaction(async (tx) => {
      const already = await tx.coinTxn.findFirst({ where: { kind: 'REFERRAL', ref: purchaseToken } });
      if (already) return;                                  // idempotent par achat
      const wallet = await tx.wallet.findUniqueOrThrow({ where: { userId: friend.referrerId! } });
      await tx.wallet.update({ where: { id: wallet.id },
        data: { bonusCoins: { increment: reward },
                bonusExpiresAt: new Date(Date.now() + 30 * 24 * 3600 * 1000) } });
      await tx.coinTxn.create({ data: { walletId: wallet.id, amount: reward,
        kind: 'REFERRAL', ref: purchaseToken } });
      await tx.referral.create({ data: { referrerId: friend.referrerId!,
        friendId, rewardCoins: reward } });
    });
    return { ok: true, reward };
  }

  async stats(userId: string) {
    const code = await this.getOrCreateCode(userId);
    const referrals = await this.prisma.referral.findMany({ where: { referrerId: userId } });
    return { code, friends: referrals.length,
             totalEarned: referrals.reduce((a, r) => a + r.rewardCoins, 0) };
  }
}
```

### 4.3 Hook dans le billing (extrait)

```typescript
// backend/src/billing/billing.service.ts (validation Google Play réussie)
async creditPurchase(userId: string, productId: string, purchaseToken: string) {
  const coins = COIN_PACKS[productId];                       // ex: coins_1200 -> 1200
  const wallet = await this.wallet.creditPurchase(userId, coins, 'PURCHASE', purchaseToken);
  // Récompense parrain : 10 % des pièces rechargées (idempotent par purchaseToken)
  await this.referral.onFriendRecharge(userId, coins, purchaseToken);
  return wallet;
}
```

### 4.4 `feature/referral/ReferralScreen.kt` (android)

```kotlin
package com.dramaforge.app.feature.referral

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

data class ReferralUiState(
    val isLoading: Boolean = true,
    val code: String = "",
    val friends: Int = 0,
    val totalEarned: Int = 0,
)

@HiltViewModel
class ReferralViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    private val _state = MutableStateFlow(ReferralUiState())
    val state: StateFlow<ReferralUiState> = _state.asStateFlow()

    init { viewModelScope.launch {
        runCatching { api.getReferralCode() }
            .onSuccess { _state.value = ReferralUiState(false, it.code, it.friends, it.totalEarned) }
            .onFailure { _state.value = ReferralUiState(false, error = it.message) }
    } }
}

@Composable
fun ReferralScreen(viewModel: ReferralViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showCopied by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Invite tes amis", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Text("Gagne 10 % des pièces qu'ils rechargent",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(24.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Ton code d'invitation", style = MaterialTheme.typography.labelMedium)
                Text(state.code, style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(state.code)); showCopied = true
                    }) { Text("Copier") }
                    Button(onClick = { shareCode(state.code) }) { Text("Partager") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Amis parrainés", "${state.friends}", Modifier.weight(1f))
            StatCard("Pièces gagnées", "🪙 ${state.totalEarned}", Modifier.weight(1f))
        }

        TextButton(onClick = { /* saisie manuelle d'un code si non parrainé */ }) {
            Text("J'ai un code d'invitation")
        }
    }
}

private fun shareCode(code: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT,
            "Regarde des dramas courts sur DramaForge ! Mon code : $code 🎬")
    }
    // startActivity(Intent.createChooser(intent, "Partager via")) depuis l'Activity hôte
}
```

*(Endpoint ajouté côté backend : `GET /referral/code` → `{ code, friends, totalEarned }` et `POST /referral/attach { code }`.)*

**DoD parrainage :** code unique 6 caractères · attach unique (409 si déjà parrainé) · récompense idempotente par `purchaseToken` · pièces gagnées en BONUS (consommées en premier, expirent 30 j) · e2e : `attach → recharge → parrain crédité 10 %` · partage natif Android.

---

## 5. ✅ Definition of Done globale du Sprint 2

| Critère | Agent |
|---|---|
| Détail : header, CTA reprise, grille épisodes 5 états, "Tout débloquer", favoris | android-developer |
| `assembleDebug` + `DetailViewModelTest` (5 états) verts | android-developer |
| Streaming : URL HMAC TTL 5 min, 403 si verrouillé, `timingSafeEqual`, 3 e2e verts | backend-developer |
| Rendu : dry-run 2 épisodes → mp4 2-3 min, HLS master 3 qualités, VTT aligné, voix stables | ai-pipeline-engineer |
| Parrainage : migration + service + hook billing + écran android + e2e idempotence | backend + android |
| CI verte sur `feat/sprint-2`, revue qa-engineer avant merge | qa-engineer |

**Sprint 3 (suite logique) :** Wallet UI + produits Play Billing (android), `billing.service.ts` validation Google Play complète (backend), écrans Bibliothèque + offline Room + téléchargements (android), notifications push FCM « nouvel épisode » (backend + android), passage du pipeline en production (cron réel + monitoring coûts).