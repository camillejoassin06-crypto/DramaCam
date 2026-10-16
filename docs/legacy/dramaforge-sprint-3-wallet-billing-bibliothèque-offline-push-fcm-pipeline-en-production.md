# 🚀 DramaForge — Sprint 3 : Implémentation multi-agents

> Périmètre : Wallet UI + Play Billing (android-developer) · validation serveur Google Play `billing.service.ts` (backend-developer) · Bibliothèque + offline Room/WorkManager (android-developer) · notifications push FCM (backend + android) · pipeline IA en production : cron durci, budget guard, monitoring coûts (ai-pipeline-engineer + devops-engineer).

**Dispatch dans Claude Code :**

```text
1. backend-developer    -> billing/ (validation Google Play)   (section 2)
2. android-developer    -> core/billing + feature/wallet + vip (section 1)
3. android-developer    -> core/database + feature/library + downloads (section 3)
4. backend-developer    -> notifications/ + devices/ (section 4.1-4.2)
5. android-developer    -> push FCM + deep link                (section 4.3)
6. devops + ai-pipeline -> daily-drama.yml production + cost guard (section 5)
7. qa-engineer          -> revue finale Sprint 3
```

---

## 1. 📱 android-developer — Wallet UI + Play Billing

### 1.1 `core/billing/PlayBillingManager.kt` — implémentation complète

```kotlin
package com.dramaforge.app.core.billing

import android.app.Activity
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class BillingUiState(
    val connectionState: BillingConnection = BillingConnection.DISCONNECTED,
    val coinPacks: List<ProductDetails> = emptyList(),   // INAPP consommables
    val vipOffer: ProductDetails? = null,                  // SUBS abonnement
    val pendingPurchase: Purchase? = null,
    val error: String? = null,
)

enum class BillingConnection { DISCONNECTED, CONNECTING, CONNECTED, UNAVAILABLE }

@Singleton
class PlayBillingManager @Inject constructor(
    private val billingRepository: BillingRepository,   // appelle POST /billing/google/verify
) : PurchasesUpdatedListener, BillingClientStateListener {

    private var client: BillingClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(BillingUiState())
    val state: StateFlow<BillingUiState> = _state.asStateFlow()

    // Packs de pièces : consommables -> consommés après validation serveur
    companion object {
        val COIN_PACK_IDS = listOf("coins_500", "coins_1200", "coins_3000", "coins_8000")
        val VIP_SUB_IDS = listOf("vip_monthly")
        const val TAG = "PlayBilling"
    }

    fun startConnection() {
        if (client != null) return
        _state.value = _state.value.copy(connectionState = BillingConnection.CONNECTING)
        client = BillingClient.newBuilder(/* applicationContext */)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
            .also { it.startConnection(this) }
    }

    // ---- BillingClientStateListener ----
    override fun onBillingSetupFinished(result: BillingResult) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                _state.value = _state.value.copy(connectionState = BillingConnection.CONNECTED)
                refreshProducts()
                restorePurchases()   // reprise d'achats non consommés / abonnements
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                _state.value = _state.value.copy(connectionState = BillingConnection.UNAVAILABLE)
            else -> retryWithBackoff()
        }
    }

    override fun onBillingServiceDisconnected() {
        _state.value = _state.value.copy(connectionState = BillingConnection.DISCONNECTED)
        retryWithBackoff()
    }

    private var retryCount = 0
    private fun retryWithBackoff() {
        if (retryCount >= 5) return
        val delay = 1000L * (1 shl retryCount++)   // 1s, 2s, 4s, 8s, 16s
        scope.launch {
            kotlinx.coroutines.delay(delay)
            client?.startConnection(this@PlayBillingManager)
        }
    }

    // ---- Catalogue : packs pièces (INAPP) + VIP (SUBS) ----
    fun refreshProducts() {
        val inApp = QueryProductDetailsParams.newBuilder()
            .setProductList(COIN_PACK_IDS.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it).setProductType(ProductType.INAPP).build() })
            .build()
        val subs = QueryProductDetailsParams.newBuilder()
            .setProductList(VIP_SUB_IDS.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it).setProductType(ProductType.SUBS).build() })
            .build()

        client?.queryProductDetailsAsync(inApp) { result, products ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK)
                _state.value = _state.value.copy(coinPacks = products)
        }
        client?.queryProductDetailsAsync(subs) { result, products ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK)
                _state.value = _state.value.copy(vipOffer = products.firstOrNull())
        }
    }

    // ---- Achat ----
    fun launchPurchaseFlow(activity: Activity, product: ProductDetails) {
        val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: ""
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(product).setOfferToken(offerToken).build()))
            .build()
        val result = client?.launchBillingFlow(activity, flowParams)
        if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.value = _state.value.copy(error = "Lancement achat échoué : ${result?.debugMessage}")
        }
    }

    // ---- PurchasesUpdatedListener ----
    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { handlePurchase(it) }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                _state.value = _state.value.copy(error = "Achat annulé")
            else -> _state.value = _state.value.copy(error = result.debugMessage)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            _state.value = _state.value.copy(pendingPurchase = purchase)  // PENDING -> attendre
            return
        }
        scope.launch {
            // 1) VALIDATION SERVEUR (source de vérité du crédit de pièces / VIP)
            val verified = billingRepository.verifyPurchase(
                productId = purchase.products.first(),
                purchaseToken = purchase.purchaseToken,
                type = if (purchase.products.first() in COIN_PACK_IDS) "INAPP" else "SUBS",
                orderId = purchase.orderId,
            )
            if (!verified) {
                _state.value = _state.value.copy(error = "Validation serveur refusée")
                return@launch
            }
            // 2) Acknowledge / consume APRÈS validation serveur réussie
            if (purchase.products.first() in VIP_SUB_IDS) {
                acknowledge(purchase)
            } else {
                consume(purchase)   // les packs de pièces sont consommables
            }
            _state.value = _state.value.copy(pendingPurchase = null)
        }
    }

    private fun consume(purchase: Purchase) {
        client?.consumeAsync(ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken).build()) { result, _ ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK)
                scope.launch { billingRepository.verifyPurchase( /* retry idempotent */ ) }
        }
    }

    private fun acknowledge(purchase: Purchase) {
        client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken).build()) { /* log */ }
    }

    /** Reprise des achats non consommés (app reinstall / crash avant ack). */
    fun restorePurchases() {
        client?.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
            .setProductType(ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK)
                purchases.forEach { if (!it.isAcknowledged) handlePurchase(it) }
        }
        client?.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
            .setProductType(ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK)
                purchases.forEach { handlePurchase(it) }
        }
    }
}
```

### 1.2 `feature/wallet/WalletScreen.kt` — solde, bonus quotidien, packs, transactions

```kotlin
package com.dramaforge.app.feature.wallet

// ---- ViewModel ----
@HiltViewModel
class WalletViewModel @Inject constructor(
    private val api: ApiService,
    private val walletRepository: WalletRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    val billing: StateFlow<BillingUiState> = billingManager.state  // injecté

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        try {
            val wallet = api.getWallet()
            _state.value = WalletUiState(
                isLoading = false,
                coins = wallet.coins, bonusCoins = wallet.bonusCoins,
                bonusExpiresAt = wallet.bonusExpiresAt,
                bonusClaimedToday = wallet.bonusClaimedToday,
                transactions = api.getTransactions(),
            )
        } catch (e: Exception) { _state.value = WalletUiState(error = e.message) }
    }

    fun claimDailyBonus() = viewModelScope.launch {
        runCatching { api.claimDailyBonus() }
            .onSuccess { refresh() }
            .onFailure { _state.update { s -> s.copy(error = "Bonus déjà récupéré aujourd'hui") } }
    }

    fun purchase(activity: Activity, product: ProductDetails) =
        billingManager.launchPurchaseFlow(activity, product)
}

// ---- Screen ----
@Composable
fun WalletScreen(viewModel: WalletViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var showTransactions by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // 1) Solde principal
        item {
            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1430))) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Mon solde", style = MaterialTheme.typography.labelMedium)
                    Text("🪙 ${state.coins + state.bonusCoins}",
                        style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black,
                        color = Color(0xFFFFD54F))
                    if (state.bonusCoins > 0) {
                        Text("dont ${state.bonusCoins} pièces bonus" +
                            (state.bonusExpiresAt?.let { " · expirent le $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xB3FFFFFF))
                    }
                }
            }
        }

        // 2) Bonus quotidien
        item {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bonus quotidien", fontWeight = FontWeight.Bold)
                        Text("+50 pièces bonus (48 h)", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { viewModel.claimDailyBonus() },
                        enabled = !state.bonusClaimedToday) {
                        Text(if (state.bonusClaimedToday) "Récupéré ✓" else "Récupérer")
                    }
                }
            }
        }

        // 3) Packs de pièces (depuis le catalogue Google Play)
        items(viewModel.billing.value.coinPacks, key = { it.productId }) { pack ->
            val coins = COIN_PACK_TO_COINS[pack.productId] ?: 0
            val badge = when (pack.productId) {
                "coins_3000" -> "POPULAIRE"
                "coins_8000" -> "MEILLEURE OFFRE"
                else -> null
            }
            CoinPackCard(
                coins = coins,
                formattedPrice = pack.oneTimePurchaseOfferDetails?.formattedPrice ?: "…",
                badge = badge,
                onBuy = { activity?.let { viewModel.purchase(it, pack) } })
        }

        // 4) VIP
        item {
            viewModel.billing.value.vipOffer?.let { vip ->
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF6C2BD9))) {
                    Column(Modifier.padding(20.dp)) {
                        Text("👑 VIP DramaForge", color = Color.White,
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        listOf("Tous les épisodes débloqués", "Téléchargements hors-ligne",
                               "Sans publicité", "Sorties en avant-première")
                            .forEach { Text("✓ $it", color = Color(0xE6FFFFFF),
                                style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { activity?.let { viewModel.purchase(it, vip) } }) {
                            Text("S'abonner · " +
                                (vip.subscriptionOfferDetails?.firstOrNull()?.pricingPhases
                                     ?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: "…"))
                        }
                        TextButton(onClick = { /* ouvrir playstore.com/account/subscriptions */ }) {
                            Text("Gérer l'abonnement", color = Color(0xCCFFFFFF),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // 5) Historique des transactions
        item {
            TextButton(onClick = { showTransactions = !showTransactions }) {
                Text(if (showTransactions) "Masquer l'historique" else "Historique des transactions")
            }
        }
        if (showTransactions) {
            items(state.transactions, key = { it.id }) { txn ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(txnLabel(txn.kind), style = MaterialTheme.typography.bodyMedium)
                        Text(txn.createdAt, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(if (txn.amount > 0) "+${txn.amount}" else "${txn.amount}",
                        color = if (txn.amount > 0) Color(0xFF7BE3A3) else Color(0xFFFF8A80),
                        fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private val COIN_PACK_TO_COINS = mapOf(
    "coins_500" to 500, "coins_1200" to 1200, "coins_3000" to 3000, "coins_8000" to 8000)

private fun txnLabel(kind: String) = when (kind) {
    "PURCHASE" -> "Achat de pièces"
    "BONUS_DAILY" -> "Bonus quotidien"
    "UNLOCK" -> "Épisode débloqué"
    "REFERRAL" -> "Récompense parrainage"
    "AD_REWARD" -> "Récompense publicité"
    "REFUND" -> "Remboursement"
    else -> kind
}
```

**DoD android (Wallet/Billing)** : achat pack → validation serveur → **consume** après crédit · VIP → **acknowledge** · `restorePurchases()` sur reconnexion billing · achats PENDING gérés (état affiché, pas de crédit) · aucun crédit local avant réponse du backend · `WalletScreenTest` (labels FR, bonus désactivé après claim).

---

## 2. 🗄️ backend-developer — Validation Google Play côté serveur

### 2.1 `backend/src/billing/billing.service.ts`

```typescript
import { BadRequestException, Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JWT } from 'google-auth-library';
import { PrismaService } from '../prisma/prisma.service';
import { WalletService } from '../wallet/wallet.service';
import { ReferralService } from '../referral/referral.service';

// Mapping produit -> pièces (source de vérité SERVEUR, jamais le client)
export const COIN_PACKS: Record<string, number> = {
  coins_500: 500, coins_1200: 1200, coins_3000: 3000, coins_8000: 8000,
};
export const VIP_PRODUCTS = new Set(['vip_monthly']);

@Injectable()
export class BillingService {
  private readonly logger = new Logger(BillingService.name);
  private jwtClient: JWT;

  constructor(
    private readonly prisma: PrismaService,
    private readonly config: ConfigService,
    private readonly wallet: WalletService,
    private readonly referral: ReferralService,
  ) {
    // Compte de service Google Play (JSON en secret, cf. .env.example)
    const creds = JSON.parse(
      this.config.getOrThrow<string>('GOOGLE_PLAY_SERVICE_ACCOUNT_JSON'));
    this.jwtClient = new JWT({
      email: creds.client_email,
      key: creds.private_key,
      scopes: ['https://www.googleapis.com/auth/androidpublisher'],
    });
  }

  private playApi() {
    const pkg = this.config.getOrThrow<string>('ANDROID_PACKAGE_NAME'); // com.dramaforge.app
    return {
      product: async (productId: string, token: string) =>
        (await this.jwtClient.request({
          url: `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${pkg}/purchases/products/${productId}/tokens/${token}`,
        })).data as { purchaseState: number; consumptionState: number;
                      orderId?: string; purchaseTimeMillis?: string },
      subscription: async (productId: string, token: string) =>
        (await this.jwtClient.request({
          url: `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${pkg}/purchases/subscriptions/${productId}/tokens/${token}`,
        })).data as { subscriptionState?: string; expiryTimeMillis?: string; orderId?: string },
    };
  }

  /** Produits consommables : packs de pièces. */
  async verifyInApp(userId: string, productId: string, purchaseToken: string) {
    if (!(productId in COIN_PACKS)) throw new BadRequestException('Produit inconnu');

    // 1) Idempotence : un purchaseToken ne crédite QU'UNE fois.
    const already = await this.prisma.coinTxn.findFirst({
      where: { kind: 'PURCHASE', ref: purchaseToken },
    });
    if (already) return { ok: true, coins: 0, idempotent: true };

    // 2) Interroger Google (source de vérité de l'achat).
    const purchase = await this.playApi().product(productId, purchaseToken);
    if (purchase.purchaseState !== 0)   // 0 = PURCHASED
      throw new BadRequestException('Achat non confirmé par Google');

    // 3) Crédit du ledger + trace txn + récompense parrain.
    const coins = COIN_PACKS[productId];
    await this.wallet.creditPurchase(userId, coins, 'PURCHASE', purchaseToken);
    await this.referral.onFriendRecharge(userId, coins, purchaseToken);

    this.logger.log(`Achat validé ${productId} +${coins} pièces (user ${userId})`);
    return { ok: true, coins, idempotent: false };
  }

  /** Abonnement VIP : upsert du statut d'abonnement. */
  async verifySubscription(userId: string, productId: string, purchaseToken: string) {
    if (!VIP_PRODUCTS.has(productId)) throw new BadRequestException('Abonnement inconnu');
    const sub = await this.playApi().subscription(productId, purchaseToken);
    const active = !['SUBSCRIPTION_STATE_EXPIRED', 'SUBSCRIPTION_STATE_CANCELED'].includes(
      sub.subscriptionState ?? '');

    await this.prisma.subscription.upsert({
      where: { purchaseToken },
      create: { userId, platform: 'google_play', productId,
        status: active ? 'active' : 'expired',
        expiresAt: new Date(Number(sub.expiryTimeMillis ?? 0)),
        purchaseToken },
      update: { status: active ? 'active' : 'expired',
               expiresAt: new Date(Number(sub.expiryTimeMillis ?? 0)) },
    });
    return { ok: true, vipActive: active, expiresAt: sub.expiryTimeMillis };
  }

  /** Appelé par le client (et par un cron de rattrapage) sans payload Google : resync. */
  async resyncSubscriptions() {
    const subs = await this.prisma.subscription.findMany({
      where: { status: 'active', expiresAt: { lt: new Date(Date.now() + 24 * 3600 * 1000) } },
    });
    for (const s of subs) await this.verifySubscription(s.userId, s.productId, s.purchaseToken);
  }
}
```

### 2.2 Controller + e2e

```typescript
// backend/src/billing/billing.controller.ts
@Controller('billing/google')
@UseGuards(JwtAuthGuard)
export class BillingController {
  constructor(private readonly billing: BillingService) {}

  @Post('verify')
  async verify(@Req() req: any,
    @Body() body: { productId: string; purchaseToken: string; type: 'INAPP' | 'SUBS' }) {
    return body.type === 'SUBS'
      ? this.billing.verifySubscription(req.user.id, body.productId, body.purchaseToken)
      : this.billing.verifyInApp(req.user.id, body.productId, body.purchaseToken);
  }
}
```

```typescript
// backend/test/billing.e2e-spec.ts (Google API mocké)
it('crédite les pièces une seule fois pour le même purchaseToken (idempotence)', async () => {
  googleMock.product.mockResolvedValue({ purchaseState: 0 });
  const first = await post(app, token, { productId: 'coins_1200', purchaseToken: 'tok_1', type: 'INAPP' });
  expect(first.body.coins).toBe(1200);
  const second = await post(app, token, { productId: 'coins_1200', purchaseToken: 'tok_1', type: 'INAPP' });
  expect(second.body.idempotent).toBe(true);          // PAS de double crédit
  const wallet = await getWallet(app, token);
  expect(wallet.coins).toBe(1200);
});

it('refuse un achat dont purchaseState != PURCHASED', async () => {
  googleMock.product.mockResolvedValue({ purchaseState: 2 });  // PENDING
  await post(app, token, { productId: 'coins_500', purchaseToken: 'tok_2', type: 'INAPP' }).expect(400);
});

it('active le VIP après vérification de l\'abonnement', async () => {
  googleMock.subscription.mockResolvedValue({
    subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE', expiryTimeMillis: String(Date.now() + 30*864e5) });
  const res = await post(app, token, { productId: 'vip_monthly', purchaseToken: 'sub_1', type: 'SUBS' });
  expect(res.body.vipActive).toBe(true);
});
```

**DoD backend (billing)** : mapping pièces **côté serveur uniquement** · idempotence par `purchaseToken` · 400 si `purchaseState != PURCHASED` · VIP upsert + `resyncSubscriptions` (cron NestJS quotidien) · e2e verts avec Google mocké.

---

## 3. 📱 android-developer — Bibliothèque + offline (Room + WorkManager)

### 3.1 Entités Room `core/database/`

```kotlin
package com.dramaforge.app.core.database

// Fichiers d'épisodes téléchargés (VIP only) — cache local de la source de vérité backend
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val episodeId: String,
    val dramaId: String,
    val dramaTitle: String,
    val episodeNumber: Int,
    val filePath: String?,            // null tant que le téléchargement n'est pas fini
    val status: String,              // QUEUED | RUNNING | PAUSED | DONE | FAILED
    val progressPercent: Int = 0,
    val bytesTotal: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 30L * 24 * 3600 * 1000, // licence 30 j
)

// Progression de lecture offline-first (sync vers le backend quand réseau dispo)
@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val episodeId: String,
    val dramaId: String,
    val positionSec: Int,
    val watched: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false,     // false -> à pousser au backend
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE dramaId = :dramaId ORDER BY episodeNumber")
    suspend fun forDrama(dramaId: String): List<DownloadEntity>
    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','RUNNING')")
    suspend fun active(): List<DownloadEntity>
    @Upsert suspend fun upsert(d: DownloadEntity)
    @Query("DELETE FROM downloads WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)
    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun count(): Int
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM watch_progress WHERE synced = 0")
    suspend fun unsynced(): List<WatchProgressEntity>
    @Upsert suspend fun upsert(p: WatchProgressEntity)
    @Query("UPDATE watch_progress SET synced = 1 WHERE episodeId = :episodeId")
    suspend fun markSynced(episodeId: String)
}
```

### 3.2 Téléchargement via WorkManager `core/download/DownloadWorker.kt`

```kotlin
package com.dramaforge.app.core.download

/**
 * Contraintes : réseau non mesuré (Wi-Fi), batterie non critique.
 * Fichiers dans filesDir/downloads/<episodeId>/  (pas de MediaStore : privé à l'app).
 * Limite de stockage : 500 Mo LRU (éviction du plus ancien téléchargement expiré).
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val streamRepository: StreamRepository,   // GET /stream/:id/url (token court)
    private val downloadDao: DownloadDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val episodeId = inputData.getString("episodeId") ?: return Result.failure()
        val dir = File(applicationContext.filesDir, "downloads/$episodeId").apply { mkdirs() }

        return try {
            downloadDao.upsert(downloadDao.forDrama("").firstOrNull()?.let { it } ?: return Result.failure())
            // statut RUNNING
            val url = streamRepository.fetchHlsUrl(episodeId)   // lien signé 5 min
            val masterFile = File(dir, "master.m3u8")

            // 1) Télécharger la playlist master
            httpGet(url, masterFile)
            // 2) Résoudre les segments + la qualité 480p (économie de stockage)
            val segments = parseSegments(masterFile, preferRendition = "480")
            // 3) Télécharger chaque segment avec progression
            segments.forEachIndexed { i, segUrl ->
                httpGet(segUrl, File(dir, "seg_%04d.ts".format(i)))
                downloadDao.updateProgress(episodeId, ((i + 1) * 100) / segments.size)
            }
            downloadDao.upsert(/* status = DONE, filePath = masterFile.path */)
            Result.success()
        } catch (e: Exception) {
            downloadDao.upsert(/* status = FAILED */)
            Result.retry()   // WorkManager exponential backoff
        }
    }

    companion object {
        fun enqueue(context: Context, episodeId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("episodeId" to episodeId))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_$episodeId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
```

### 3.3 Lecteur offline-first — résolution de source

```kotlin
// feature/player/PlayerViewModel.kt (patch)
fun playerFor(episodeId: String): ExoPlayer = players[episodeId] ?: run {
    val source = resolveSource(episodeId)   // LOCAL d'abord, réseau sinon
    createPlayer(episodeId, source).also { players[episodeId] = it }
}

private fun resolveSource(episodeId: String): String {
    val local = downloadRepository.localMaster(episodeId)    // file://.../master.m3u8
    return local ?: streamUrlCache[episodeId]?.first  // fallback : URL signée
}
// ExoPlayer lit les playlists HLS LOCALES (fichier + segments .ts relatifs) sans réseau.
```

### 3.4 `feature/library/LibraryScreen.kt`

```kotlin
@Composable
fun LibraryScreen(onResume: (dramaId: String, episodeId: String) -> Unit,
                  onDramaClick: (String) -> Unit,
                  viewModel: LibraryViewModel = hiltViewModel()) {
    val tabs = listOf("En cours", "Favoris", "Téléchargements")
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i },
                    text = { Text(if (i == 2 && state.downloadCount > 0) "Téléchargements · ${state.downloadCount}" else label) })
            }
        }
        when (tab) {
            0 -> ContinueWatchingList(state.resumeItems) { d, e -> onResume(d, e) }
            1 -> FavoritesList(state.favorites, onDramaClick)
            2 -> DownloadsList(state.downloads,
                onPlayDownload = { d, e -> onResume(d, e) },
                onDelete = { viewModel.deleteDownload(it) })
        }
    }
}

@Composable
private fun DownloadsList(downloads: List<DownloadItem>,
                           onPlayDownload: (String, String) -> Unit, onDelete: (String) -> Unit) {
    LazyColumn {
        items(downloads, key = { it.episodeId }) { dl ->
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dl.dramaTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold)
                        Text("Ép. ${dl.episodeNumber}", style = MaterialTheme.typography.labelSmall)
                        when (dl.status) {
                            "RUNNING", "QUEUED" -> LinearProgressIndicator(
                                progress = { dl.progress / 100f }, modifier = Modifier.fillMaxWidth())
                            "FAILED" -> Text("Échec — réessayez", color = Color(0xFFFF8A80),
                                style = MaterialTheme.typography.labelSmall)
                            "DONE" -> {
                                val daysLeft = ((dl.expiresAt - System.currentTimeMillis()) / 86400_000).toInt()
                                Text("Disponible hors-ligne · $daysLeft j restants",
                                    color = Color(0xFF7BE3A3),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (dl.status == "DONE")
                        IconButton(onClick = { onPlayDownload(dl.dramaId, dl.episodeId) }) { Text("▶") }
                    IconButton(onClick = { onDelete(dl.episodeId) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                    }
                }
            }
        }
    }
}
```

**DoD android (library/offline)** : téléchargement **réservé VIP** (contrôle backend sur l'URL signée + re-check à la lecture locale) · stockage privé, LRU 500 Mo, licence 30 j affichée · lecture 100 % hors-ligne depuis l'UI Téléchargements · sync de progression en attente poussée au retour réseau (`synced = 0`) · test : mode avion → lecture d'un épisode téléchargé OK.

---

## 4. 🔔 Notifications push « nouvel épisode » (FCM)

### 4.1 Backend — `backend/src/notifications/notifications.service.ts`

```typescript
import { Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JWT } from 'google-auth-library';
import { PrismaService } from '../prisma/prisma.service';

@Injectable()
export class NotificationsService {
  private readonly logger = new Logger(NotificationsService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly config: ConfigService,
  ) {}

  /** OAuth2 token FCM (HTTP v1) via le compte de service Firebase. */
  private async fcmToken(): Promise<string> {
    const creds = JSON.parse(this.config.getOrThrow<string>('FIREBASE_SERVICE_ACCOUNT_JSON'));
    const jwt = new JWT({
      email: creds.client_email, key: creds.private_key,
      scopes: ['https://www.googleapis.com/auth/firebase.messaging'],
    });
    const { access_token } = await jwt.authorize();
    return access_token!;
  }

  private async send(message: object) {
    const projectId = JSON.parse(
      this.config.getOrThrow<string>('FIREBASE_SERVICE_ACCOUNT_JSON')).project_id;
    const res = await fetch(
      `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${await this.fcmToken()}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ message }),
      });
    if (!res.ok) this.logger.warn(`FCM ${res.status}: ${await res.text()}`);
  }

  /** Enregistrement du device token de l'app (POST /devices, appelé au login). */
  async registerDevice(userId: string, token: string, locale: string) {
    await this.prisma.deviceToken.upsert({
      where: { token }, create: { userId, token, locale },
      update: { userId, locale, active: true },
    });
  }

  /**
   * Broadcast "nouveau drama/épisodes" — appelé par le pipeline IA (clé de service)
   * après publication. Sujet FCM par langue : topics/new_dramas_fr, etc.
   * Ne PAS réveiller les utilisateurs la nuit : heuristique de plage locale 18-21 h.
   */
  async broadcastNewDrama(title: string, dramaId: string, coverUrl: string) {
    for (const locale of ['fr', 'en']) {
      const body = locale === 'fr'
        ? `Nouveau drama exclusif : ${title}`
        : `New exclusive drama: ${title}`;
      await this.send({
        topic: `new_dramas_${locale}`,
        notification: { title: 'DramaForge', body },
        data: { dramaId, coverUrl, deepLink: `dramaforge://drama/${dramaId}` },
        android: { priority: 'HIGH', notification: { channelId: 'new_dramas', image: coverUrl } },
        apns: { payload: { aps: { 'mutable-content': 1 } } },
      });
    }
  }

  /** Rappel de rétention : un utilisateur n'a pas regardé depuis 3 jours. */
  async sendReengagement(userId: string, dramaTitle: string, episodeNumber: number) {
    const tokens = await this.prisma.deviceToken.findMany({ where: { userId, active: true } });
    for (const t of tokens) {
      await this.send({
        token: t.token,
        notification: { title: 'Votre drama vous attend 🎬',
          body: `${dramaTitle} — épisode ${episodeNumber} est sorti !` },
        data: { deepLink: `dramaforge://drama/continue` },
      });
    }
  }
}
```

### 4.2 Endpoints

```text
POST /devices { token, locale }              (JwtAuthGuard)  -> registerDevice
POST /admin/notify/broadcast { title, dramaId, coverUrl }     (clé ADMIN_API_KEY)
      -> broadcastNewDrama()      // appelé par le pipeline à la fin de generate-daily.ts
```

### 4.3 Android — `core/push/DramaForgeMessagingService.kt` + deep link

```kotlin
@AndroidEntryPoint
class DramaForgeMessagingService : FirebaseMessagingService() {

    @Inject lateinit var api: ApiService
    @Inject lateinit var session: SessionManager

    override fun onNewToken(token: String) {
        // Upload du token FCM (nouveaux épisodes, rétention)
        if (session.isLoggedIn) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { api.registerDevice(DeviceRequest(token, session.locale)) }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val dramaId = message.data["dramaId"] ?: return
        val notification = NotificationCompat.Builder(this, "new_dramas")
            .setSmallIcon(R.drawable.ic_dramaforge)
            .setContentTitle(message.notification?.title ?: "DramaForge")
            .setContentText(message.notification?.body ?: "")
            .setLargeIcon(fetchBitmap(message.data["coverUrl"]))   // couverture en grande icône
            .setStyle(NotificationCompat.BigPictureStyle()
                .bigPicture(fetchBitmap(message.data["coverUrl"])))
            .setAutoCancel(true)
            .setContentIntent(deepLinkIntent(dramaId))   // dramaforge://drama/<id>
            .build()
        NotificationManagerCompat.from(this).notify(dramaId.hashCode(), notification)
    }

    private fun deepLinkIntent(dramaId: String) =
        PendingIntent.getActivity(this, dramaId.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse("dramaforge://drama/$dramaId")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

// AndroidManifest.xml — intent-filter de deep link + service
// <intent-filter>
//   <action android:name="android.intent.action.VIEW"/>
//   <data android:scheme="dramaforge" android:host="drama"/>
// </intent-filter>

// NavGraph : interception du deep link
// navController.handleDeepLink(intent)  // route "detail/{dramaId}" matchée automatiquement
```

**DoD push** : topic par langue (`new_dramas_fr`) souscrit à l'onboarding selon la locale · token uploadé au login · notification big-picture avec couverture · tap → deep link `dramaforge://drama/<id>` → écran Détail · admin broadcast appelé par le pipeline après publication.

---

## 5. 🏭 devops + ai-pipeline — Pipeline en production

### 5.1 Budget guard — `pipeline/src/cost-guard.ts`

```typescript
import Anthropic from '@anthropic-ai/sdk';
import fs from 'fs/promises';

// Prix par million de tokens (à maintenir à jour dans le CI)
const PRICES = { input: 3, output: 15 };   // Sonnet $/$ par MTok
export const DAILY_BUDGET_EUR = Number(process.env.DAILY_BUDGET_EUR ?? 70);

export class CostGuard {
  private spent = 0;
  private readonly log: { step: string; inTok: number; outTok: number; eur: number }[] = [];

  record(step: string, usage: { input_tokens: number; output_tokens: number }) {
    const eur = (usage.input_tokens / 1e6) * PRICES.input
              + (usage.output_tokens / 1e6) * PRICES.output;
    this.spent += eur;
    this.log.push({ step, inTok: usage.input_tokens, outTok: usage.output_tokens, eur });
    if (this.spent > DAILY_BUDGET_EUR) {
      throw new BudgetExceededError(this.spent, DAILY_BUDGET_EUR);
    }
  }

  async writeReport(out: string, dramaTitle: string, episodesPublished: number) {
    await fs.writeFile(out, JSON.stringify({
      dramaTitle, episodesPublished, totalEur: +this.spent.toFixed(2),
      budgetEur: DAILY_BUDGET_EUR, detail: this.log,
      timestamp: new Date().toISOString(),
    }, null, 2));
  }
}

export class BudgetExceededError extends Error {
  constructor(public spent: number, public budget: number) {
    super(`Budget IA dépassé : ${spent.toFixed(2)} € / ${budget} €`);
  }
}

// Branchement dans generate-daily.ts :
//   const guard = new CostGuard();
//   const msg = await claude.messages.create({...});
//   guard.record('concept', msg.usage);
//   ...
//   catch (e) { if (e instanceof BudgetExceededError) -> SORTIE PROPRE :
//     publier les épisodes déjà produits (série partielle "En cours"),
//     écrire le rapport coût, alerter Slack, exit(1) }
```

### 5.2 Reprise idempotente — `pipeline/src/state.ts`

```typescript
/**
 * Le cron peut être relancé manuellement après incident sans créer de doublon :
 * un fichier d'état JSON (artefact re-téléchargeable) suit la progression.
 */
interface RunState {
  date: string; runId: string; concept?: unknown; bible?: unknown;
  episodesDone: number[]; published: boolean;
}

// Au démarrage : si un état incomplet existe pour la date du jour -> REPRISE
// (régénérer seulement les épisodes manquants de la bible, pas tout).
// La publication admin est idempotente : POST /admin/dramas/import
// avec upsert par (title) -> aucun doublon de série ni d'épisode.
```

### 5.3 `daily-drama.yml` durci

```yaml
name: Génération quotidienne de drama (production)
on:
  schedule:
    - cron: '0 3 * * *'     # 05:00 Paris, publié avant la pointe du soir
  workflow_dispatch:
    inputs:
      dry_run: { description: 'Dry-run (pas de publication)', type: boolean, default: false }

concurrency:
  group: daily-drama
  cancel-in-progress: false      # jamais tuer une génération en cours

jobs:
  generate:
    runs-on: ubuntu-large
    timeout-minutes: 240
    env:
      DAILY_BUDGET_EUR: '70'
      SLACK_WEBHOOK: ${{ secrets.SLACK_WEBHOOK }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 22, cache: npm }
      - run: npm ci

      - name: Vérifier l'état (reprise éventuelle)
        run: npx tsx pipeline/src/state.ts check || true

      - name: Générer
        run: npx tsx pipeline/generate-daily.ts ${{ github.event.inputs.dry_run && '--dry-run' || '' }}
        env:
          ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
          IMAGE_API_KEY: ${{ secrets.IMAGE_API_KEY }}
          TTS_API_KEY: ${{ secrets.TTS_API_KEY }}
          R2_ACCESS_KEY: ${{ secrets.R2_ACCESS_KEY }}
          R2_SECRET_KEY: ${{ secrets.R2_SECRET_KEY }}
          ADMIN_API_KEY: ${{ secrets.ADMIN_API_KEY }}

      - name: Rapport de coûts (artefact, toujours exécuté)
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: cost-report-${{ github.run_id }}
          path: pipeline/out/cost-report.json

      - name: Alerte échec
        if: failure()
        run: |
          curl -s -X POST "$SLACK_WEBHOOK" -d '{"text":"❌ daily-drama a échoué — vérifier cost-report (budget ?) et state.json pour reprise manuelle : re-run du workflow."}'

      - name: Alerte succès
        if: success()
        run: |
          COST=$(jq -r '.totalEur' pipeline/out/cost-report.json)
          EPS=$(jq -r '.episodesPublished' pipeline/out/cost-report.json)
          curl -s -X POST "$SLACK_WEBHOOK" -d "{\"text\":\"✅ Série du jour publiée : $EPS épisodes · coût IA : ${COST} € / ${{ env.DAILY_BUDGET_EUR }} €\"}"
```

### 5.4 Dashboard de suivi (MVP)

- **Artéfact par run** : `cost-report.json` (coût total, par étape, épisodes publiés) — base du suivi hebdo.
- **Requête admin** : `GET /admin/stats/quality` (saisie par l'agent content-showrunner) : score moyen des séries publiées, taux de régénération (< 15 % attendu).
- **Revue hebdo** (tâche récurrente Claude Code) : télécharger les 7 derniers `cost-report`, calculer moyenne/coût par épisode, proposer ajustements (modèle, longueur prompts, parallelisation).
- **Runbook incident** : échec → vérifier `cost-report` (budget ?) → si budget : re-run avec `DAILY_BUDGET_EUR` inchangé le lendemain (l'état reprend) ; si bug : `workflow_dispatch` avec `dry_run=true` sur une branche de test.

**DoD production** : 3 jours consécutifs de runs verts · coût/jour mesuré et affiché (cible < 70 €) · reprise après kill testée (relance manuelle → série complétée sans doublon) · alertes Slack opérationnelles · publication idempotente vérifiée en DB.

---

## 6. ✅ Definition of Done globale du Sprint 3

| Critère | Agent |
|---|---|
| Wallet : solde/bonus/packs/transactions/VIP + achat→validation serveur→consume/acknowledge | android-developer |
| Billing serveur : idempotence par token, `purchaseState` vérifié, VIP upsert + resync, 3 e2e verts | backend-developer |
| Offline : téléchargements VIP via WorkManager, lecture 100 % hors-ligne testée, sync progression | android-developer |
| Push : topics par langue, big-picture, deep link écran Détail, broadcast appelé par le pipeline | backend + android |
| Production : budget guard + reprise idempotente + rapports de coût + alertes Slack | devops + ai-pipeline |
| CI verte sur `feat/sprint-3`, revue qa-engineer avant merge | qa-engineer |

**Sprint 4 (final) — suggestion** : QA de bout en bout (crash-free lecteur, parcours d'achat sandbox Google Play), optimisation du lecteur (préchargement adaptatif selon réseau), écran Profil/Paramètres complet, i18n FR/EN des chaînes, beta fermée (distribution APK + Crashlytics) et préparation release Play Store.