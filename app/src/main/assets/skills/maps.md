# maps — interactive maps, markers, routes, addresses and distances

- `KMap` shows OpenStreetMap (no API key). Needs `android.permission.INTERNET` in kiln.json permissions.
- Markers: `KMarker(KLatLng(lat, lng), title, subtitle) { onClick }`. Route: `route = listOf(KLatLng…)` draws a line.
- Tap to pick a point: `onTap = { latLng -> }`; long-press for "drop a pin".
- My location: `showMyLocation = true` plus ACCESS_FINE_LOCATION (or COARSE) in kiln.json and granted with
  `rememberPermission`. `KLocation.current(context)` gives one fix to centre the map on.
- Addresses: `KGeo.find(context, "Brandenburg Gate")` → KLatLng?, `KGeo.address(context, latLng)` → String?
  (suspend; both need internet). Distances: `KGeo.distanceMeters(a, b)`, shown with `KGeo.formatDistance`.
- Give the map a fixed `height` inside scrolling screens, or `height = null` with `Modifier.weight(1f)` for a full-screen map.

```kotlin
import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kiln.kit.*
import kotlinx.coroutines.launch

data class Place(val name: String, val at: KLatLng)

@Composable
fun PlacesMap() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val location = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    var center by remember { mutableStateOf(KLatLng(52.5163, 13.3777)) }
    var picked by remember { mutableStateOf<KLatLng?>(null) }
    var address by remember { mutableStateOf<String?>(null) }
    val places = listOf(Place("Gate", KLatLng(52.5163, 13.3777)), Place("Tower", KLatLng(52.5208, 13.4094)))

    KilnScreen("Places") { padding ->
        Column(Modifier.screenPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KMap(center, zoom = 13.0, height = 320.dp, showMyLocation = location.granted,
                markers = places.map { KMarker(it.at, it.name) }, route = places.map { it.at },
                onTap = { p -> picked = p; scope.launch { address = KGeo.address(context, p) } })
            KButton("Center on me", variant = KVariant.Tonal) {
                if (!location.granted) location.request() else scope.launch { KLocation.current(context)?.let { center = KLatLng(it.latitude, it.longitude) } }
            }
            picked?.let { p ->
                KKeyValue("Picked", address ?: "%.4f, %.4f".format(p.lat, p.lng))
                KKeyValue("To the gate", KGeo.formatDistance(KGeo.distanceMeters(p, places[0].at)))
            }
            Text("Map data © OpenStreetMap contributors", color = Nocturne.textMuted)
        }
    }
}
```
