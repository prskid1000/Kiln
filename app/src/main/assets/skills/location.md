# location — get the current position once (no Google Play services)

- Declare ACCESS_FINE_LOCATION (or ACCESS_COARSE_LOCATION) with `set_app_meta`; ask with `rememberPermission`.
- `LocationManager.getCurrentLocation` (API 30+) gives one fix. Prefer the fused provider when present.
- Location can be off or unavailable: handle null and show a message.

```kotlin
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@SuppressLint("MissingPermission")
suspend fun skillCurrentLocation(context: Context): Location? = suspendCancellableCoroutine { cont ->
    val lm = context.getSystemService(LocationManager::class.java)
    val provider = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { lm.allProviders.contains(it) && lm.isProviderEnabled(it) }
    if (provider == null) { cont.resume(null); return@suspendCancellableCoroutine }
    val cancel = android.os.CancellationSignal()
    cont.invokeOnCancellation { cancel.cancel() }
    lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
}

val skillLocationPermission = Manifest.permission.ACCESS_FINE_LOCATION
```
