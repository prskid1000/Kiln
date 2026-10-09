package app.kiln.kit

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Google Play in-app purchases. Opt in first: add the permission `com.android.vending.BILLING`
 * with `set_app_meta` (that's what brings Play Billing into the app's manifest), and create the
 * products in Play Console. Purchases only work for builds installed from Play (internal testing
 * is enough) by a tester account.
 *
 * ```
 * val billing = rememberBilling()                       // disconnects when the screen goes away
 * val owned by billing.owned.collectAsState()          // product ids the user owns
 * LaunchedEffect(Unit) { billing.load(listOf("pro_upgrade")); billing.refresh() }
 * val pro = billing.product("pro_upgrade")             // ProductDetails?, for its price
 * // A finished purchase updates `owned` by itself.
 * Button(onClick = { billing.buy(activity, "pro_upgrade") }) { Text(pro?.oneTimePurchaseOfferDetails?.formattedPrice ?: "Buy") }
 * ```
 */
class KBilling(context: Context, private val consumable: Set<String> = emptySet()) {
    private val _owned = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of non-consumable products and active subscriptions the user owns. */
    val owned: StateFlow<Set<String>> = _owned
    private val details = HashMap<String, ProductDetails>()
    /** Called once per completed consumable purchase (grant the coins, credits, …). */
    // Null until the app sets it: a consumable is only consumed once something will grant it (consumed with nobody
    // listening, the coins were lost for good — Play no longer lists a consumed purchase).
    var onConsumed: ((productId: String) -> Unit)? = null

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases?.let { handlePending(it) } }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    // Appended on the main thread, taken on an IO thread: atomic, so an update landing mid-refresh isn't dropped.
    private val pending = java.util.concurrent.atomic.AtomicReference<List<Purchase>>(emptyList())
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    // A purchase just finished: acknowledge (or consume) it and update [owned] right away.
    private fun handlePending(list: List<Purchase>) {
        // Added to: a second update must not drop purchases not processed yet.
        pending.updateAndGet { it + list }; KLog.i("billing: ${list.size} purchase(s) updated")
        scope.launch { refresh() }
    }

    /** Connect to Play; true when ready. */
    suspend fun connect(): Boolean {
        if (client.isReady) return true
        // One connection attempt at a time: a second startConnection while connecting fails (load + refresh together).
        val done = synchronized(this) {
            connecting?.takeIf { !it.isCompleted } ?: CompletableDeferred<Boolean>().also { d ->
                connecting = d
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) { d.complete(result.responseCode == BillingClient.BillingResponseCode.OK) }
                    override fun onBillingServiceDisconnected() { if (!d.isCompleted) d.complete(false) }
                })
            }
        }
        return done.await()
    }
    private var connecting: CompletableDeferred<Boolean>? = null

    /** Disconnect from Play (call when the screen holding it goes away; [rememberBilling] does). */
    fun close() { runCatching { client.endConnection() }; scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    /** Load product details (prices) for these ids. */
    suspend fun load(ids: List<String>, type: String = BillingClient.ProductType.INAPP) {
        if (!connect()) return
        val params = QueryProductDetailsParams.newBuilder().setProductList(ids.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
        }).build()
        client.queryProductDetails(params).productDetailsList?.forEach { details[it.productId] = it }
    }

    fun product(id: String): ProductDetails? = details[id]

    /** Start buying [id] (load it first). Returns false when it can't start. */
    fun buy(activity: Activity, id: String): Boolean {
        val d = details[id] ?: return false
        val p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d)
            .apply { d.subscriptionOfferDetails?.firstOrNull()?.let { setOfferToken(it.offerToken) } }.build()
        return client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(p)).build())
            .responseCode == BillingClient.BillingResponseCode.OK
    }

    /** Re-read what the user owns, and finish new purchases (acknowledge, or consume consumables). */
    suspend fun refresh() {
        if (!connect()) return
        val results = listOf(BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS).map { type ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build())
        }
        // A query that fails isn't "owns nothing" (subscriptions unsupported on the device just means none). New purchases
        // are always finished, and on a failure what the user owns is only added to, never taken away.
        fun ok(r: com.android.billingclient.api.PurchasesResult) = r.billingResult.responseCode == BillingClient.BillingResponseCode.OK ||
            r.billingResult.responseCode == BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED
        val complete = results.all(::ok)
        val all = results.filter(::ok).flatMap { it.purchasesList } + pending.getAndSet(emptyList())
        val owned = mutableSetOf<String>()
        for (p in all.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.distinctBy { it.purchaseToken }) {
            val ids = p.products
            if (ids.any { it in consumable }) {
                val grant = onConsumed ?: continue   // left for a later refresh, once the app is listening
                val r = client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
                // The grant runs on the main thread (a Toast or UI update in it crashed the app on IO, after the
                // purchase was already consumed), and its failure is logged, not fatal.
                if (r.billingResult.responseCode == BillingClient.BillingResponseCode.OK)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        runCatching { ids.forEach(grant) }.onFailure { KLog.w("billing: granting ${ids} failed: ${it.message}") } }
            } else {
                if (!p.isAcknowledged) client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
                owned += ids
            }
        }
        _owned.value = if (complete) owned else _owned.value + owned
    }
}

/** A [KBilling] for this screen, disconnected from Play when it leaves composition. */
@androidx.compose.runtime.Composable
fun rememberBilling(consumable: Set<String> = emptySet(), onConsumed: ((productId: String) -> Unit)? = null): KBilling {
    val context = androidx.compose.ui.platform.LocalContext.current
    val billing = androidx.compose.runtime.remember(consumable) { KBilling(context, consumable) }
    billing.onConsumed = onConsumed ?: billing.onConsumed
    androidx.compose.runtime.DisposableEffect(billing) { onDispose { billing.close() } }
    return billing
}
