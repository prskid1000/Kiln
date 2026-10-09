# billing — in-app purchases with KBilling (Google Play)

- Opt in first: `set_app_meta` with permission `com.android.vending.BILLING` (this brings Play
  Billing into the manifest). Tell the user to create the products in Play Console with the same ids.
- Purchases only work in a build installed from Play (internal testing is enough) by a tester;
  on a Kiln-installed build `buy` shows an error — say so, don't treat it as a bug.
- Non-consumables (a "Pro" unlock) appear in `owned`. Consumables (coins) need both: list them and grant them,
  `var coins by rememberPref("coins", 0)` then `rememberBilling(consumable = setOf("coins_100"), onConsumed = { id -> coins += 100 })`.
  What's granted must be saved (rememberPref or a KStore): a consumed purchase can't be restored from Play — without `consumable` they're
  treated as a one-time unlock (bought once, then "already owned"); without `onConsumed` they're left unconsumed.
- Never decide entitlement from a local flag alone: re-check `owned` after `refresh()` on start.

```kotlin
import android.app.Activity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.kiln.kit.KBilling
import app.kiln.kit.rememberBilling

@Composable
fun ProUpgrade(productId: String = "pro_upgrade") {
    val context = LocalContext.current
    val billing = rememberBilling()          // ends the Play connection when the screen goes away
    val owned by billing.owned.collectAsState()
    var price by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        billing.load(listOf(productId))
        price = billing.product(productId)?.oneTimePurchaseOfferDetails?.formattedPrice
        billing.refresh()
    }
    if (productId in owned) Text("Pro unlocked — thank you!")
    else Button(onClick = { (context as? Activity)?.let { billing.buy(it, productId) } }) {
        Text(if (price != null) "Upgrade to Pro · $price" else "Upgrade to Pro")
    }
}
```
