package app.kiln.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.kiln.agent.Activity
import app.kiln.agent.Turns
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import app.kiln.ui.theme.vCard
import app.kiln.ui.theme.vInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A QA recording, played inline (tap for controls). */
@Composable
fun QaVideo(path: String, modifier: Modifier = Modifier) {
    if (!File(path).isFile) return
    // A VideoView stretches to whatever box it gets, so size the box to the video's own shape.
    // Read off the main thread (it opens the file); a phone-shaped box until it's known.
    val ratio by androidx.compose.runtime.produceState(9f / 19.5f, path) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            android.media.MediaMetadataRetriever().use { r ->
                r.setDataSource(path)
                val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat()
                val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
                val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot % 180 == 0) w / h else h / w
            }
        }.getOrDefault(9f / 19.5f)
    } }
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.CenterStart) {
        AndroidView(factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(Uri.fromFile(File(path)))
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                setOnPreparedListener { it.isLooping = true; start() }
            }
        }, modifier = Modifier.height(320.dp).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)))
    }
}

/** Rules the agent proposed after a correction; Save appends them to the project's memory. */
@Composable
fun RuleProposals(vm: KilnVM, ps: ProjectState, feed: List<Activity>) {
    val decided by ps.decidedRules.collectAsState()
    val pending = feed.filter { it.kind == Activity.Kind.TOOL && it.tool == "propose_rule" && it.status == Activity.Status.DONE && it.id !in decided }
    val a = pending.firstOrNull() ?: return
    val rule = runCatching { (app.kiln.core.parseJson(a.input ?: "{}") as kotlinx.serialization.json.JsonObject)["rule"]
        ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } }.getOrNull() ?: return
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().vCard(N.shapeLg, N.accent.copy(alpha = 0.5f))
        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Lightbulb, null, tint = N.accent2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Remember for this app?", style = T.label)
            Text(rule, style = T.body)
        }
        KButton("Dismiss") { ps.decidedRules.value = decided + a.id }
        Spacer(Modifier.width(6.dp))
        KButton("Save", Tone.Accent) { ps.decidedRules.value = decided + a.id; vm.saveRule(ps.project.name, rule) }
    }
}
