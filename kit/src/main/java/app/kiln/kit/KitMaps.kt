package app.kiln.kit

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------- maps

/** A point on Earth. */
data class KLatLng(val lat: Double, val lng: Double)

/** A pin on a [KMap]; [onClick] runs when it's tapped (its title shows in a bubble too). */
data class KMarker(val at: KLatLng, val title: String = "", val subtitle: String = "", val onClick: (() -> Unit)? = null)

/**
 * An interactive map (OpenStreetMap — no API key, needs INTERNET). Pinch to zoom, drag to pan.
 * ```
 * KMap(center = KLatLng(52.52, 13.405), zoom = 13.0, height = 300.dp,
 *      markers = places.map { KMarker(KLatLng(it.lat, it.lng), it.name) },
 *      onTap = { picked = it })
 * ```
 * [route] draws a line through its points; [showMyLocation] needs a granted location permission.
 */
@Composable
fun KMap(
    center: KLatLng,
    modifier: Modifier = Modifier,
    zoom: Double = 13.0,
    markers: List<KMarker> = emptyList(),
    route: List<KLatLng> = emptyList(),
    showMyLocation: Boolean = false,
    height: Dp? = 280.dp,
    corner: Dp = kr(16),
    onTap: ((KLatLng) -> Unit)? = null,
    onLongPress: ((KLatLng) -> Unit)? = null,
) {
    val context = LocalContext.current
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = java.io.File(context.cacheDir, "osm"); osmdroidTileCache = java.io.File(context.cacheDir, "osm/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(zoom)
            controller.setCenter(GeoPoint(center.lat, center.lng))
        }
    }
    DisposableEffect(map) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    var lastCenter by remember { mutableStateOf(center) }
    // OpenStreetMap's tile policy requires this credit on the map.
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier).clip(RoundedCornerShape(corner))) {
    AndroidView({ map }, Modifier.matchParentSize()) { mv ->
        if (center != lastCenter) { mv.controller.animateTo(GeoPoint(center.lat, center.lng)); lastCenter = center }
        mv.overlays.clear()
        mv.overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { tap?.invoke(KLatLng(p.latitude, p.longitude)); return tap != null }
            override fun longPressHelper(p: GeoPoint): Boolean { long?.invoke(KLatLng(p.latitude, p.longitude)); return long != null }
        })
        if (route.size > 1) mv.overlays += Polyline(mv).apply {
            setPoints(route.map { GeoPoint(it.lat, it.lng) })
            outlinePaint.color = android.graphics.Color.argb((Nocturne.accent.alpha * 255).toInt(), (Nocturne.accent.red * 255).toInt(), (Nocturne.accent.green * 255).toInt(), (Nocturne.accent.blue * 255).toInt()); outlinePaint.strokeWidth = 10f
        }
        markers.forEach { m ->
            mv.overlays += Marker(mv).apply {
                position = GeoPoint(m.at.lat, m.at.lng); title = m.title; snippet = m.subtitle
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { mk, _ -> m.onClick?.invoke(); if (m.title.isNotEmpty()) mk.showInfoWindow(); true }
            }
        }
        if (showMyLocation && hasLocation(context)) mv.overlays += MyLocationNewOverlay(GpsMyLocationProvider(context), mv).apply { enableMyLocation() }
        mv.invalidate()
    }
    androidx.compose.material3.Text("© OpenStreetMap contributors", fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp),
        color = androidx.compose.ui.graphics.Color(0xFF333333),
        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).background(androidx.compose.ui.graphics.Color(0xCCFFFFFF))
            .padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

private fun hasLocation(c: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    .any { c.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

/** Addresses ↔ coordinates (the system geocoder; needs internet) and distances. */
object KGeo {
    /** Coordinates for an address or place name, or null. */
    @Suppress("DEPRECATION")
    suspend fun find(context: Context, address: String): KLatLng? = withContext(Dispatchers.IO) {
        runCatching { Geocoder(context, Locale.getDefault()).getFromLocationName(address, 1)?.firstOrNull()?.let { KLatLng(it.latitude, it.longitude) } }.getOrNull()
    }
    /** A readable address for a point, or null. */
    @Suppress("DEPRECATION")
    suspend fun address(context: Context, at: KLatLng): String? = withContext(Dispatchers.IO) {
        runCatching { Geocoder(context, Locale.getDefault()).getFromLocation(at.lat, at.lng, 1)?.firstOrNull()?.getAddressLine(0) }.getOrNull()
    }
    /** Straight-line distance in metres. */
    fun distanceMeters(a: KLatLng, b: KLatLng): Double {
        val r = 6_371_000.0; val dLat = Math.toRadians(b.lat - a.lat); val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
    /** "850 m" or "12.4 km". */
    fun formatDistance(meters: Double): String = if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000)
}

// ---------------------------------------------------------------- graphics

/**
 * Shows an SVG (from assets — `"file:///android_asset/logo.svg"` — a URL, or a file), scaled to fit.
 * For icons the app draws itself, prefer a vector drawable in res/drawable.
 */
@Composable
fun KSvg(source: String, description: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = remember { ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build() }
    AsyncImage(ImageRequest.Builder(context).data(source).build(), description, loader, modifier)
}

// ---------------------------------------------------------------- speech

/**
 * In-app speech to text (no system dialog), with live partial results. Needs RECORD_AUDIO
 * (kiln.json permissions + [rememberPermission]).
 * ```
 * val mic = rememberSpeechToText()
 * KButton(if (mic.listening) "Stop" else "Speak") { if (mic.listening) mic.stop() else mic.start() }
 * Text(mic.text)            // updates while the user talks; final when listening turns false
 * ```
 */
class KSpeechToText internal constructor(private val context: Context, private val locale: Locale) {
    var text by mutableStateOf(""); internal set
    var listening by mutableStateOf(false); internal set
    var error by mutableStateOf<String?>(null); internal set
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    @SuppressLint("MissingPermission")
    fun start() {
        if (!available) { error = "Speech recognition isn't available on this device"; return }
        stop(); text = ""; error = null
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { listening = true }
                override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text = it } }
                override fun onResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text = it }; listening = false }
                override fun onError(code: Int) { listening = false; if (code != SpeechRecognizer.ERROR_NO_MATCH) error = "Speech error $code" }
                override fun onEndOfSpeech() {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }
    }

    fun stop() { recognizer?.run { stopListening(); destroy() }; recognizer = null; listening = false }
}

@Composable
fun rememberSpeechToText(locale: Locale = Locale.getDefault()): KSpeechToText {
    val context = LocalContext.current
    val stt = remember { KSpeechToText(context, locale) }
    DisposableEffect(stt) { onDispose { stt.stop() } }
    return stt
}
