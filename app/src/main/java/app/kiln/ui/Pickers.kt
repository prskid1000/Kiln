package app.kiln.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kiln.agent.Attachment
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Files over this size are refused: they'd blow the model's context and the APK. */
const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024

class AttachmentPicker(val photo: () -> Unit, val file: () -> Unit)

/**
 * System pickers (Photo Picker for images, the document picker for anything): no storage
 * permission needed. Picked files are read into memory and handed to [onPicked].
 */
@Composable
fun rememberAttachmentPicker(onError: (String) -> Unit = {}, onPicked: (List<Attachment>) -> Unit): AttachmentPicker {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun load(uris: List<Uri>) = scope.launch {
        val out = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                val cr = ctx.contentResolver
                var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                var size = -1L
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
                }
                if (size > MAX_UPLOAD_BYTES) { withContext(Dispatchers.Main) { onError("$name is over 20 MB") }; return@mapNotNull null }
                val bytes = runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@mapNotNull null
                Attachment(name, cr.getType(uri) ?: "application/octet-stream", bytes)
            }
        }
        if (out.isNotEmpty()) onPicked(out)
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { load(it) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { load(it) }
    return remember {
        AttachmentPicker(
            photo = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            file = { docs.launch(arrayOf("*/*")) },
        )
    }
}

/** A pending or sent attachment: thumbnail (images) or file icon, name, optional remove. */
@Composable
fun AttachmentChip(name: String, image: ByteArray?, onRemove: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.clip(shape).background(N.surfaceHi).border(1.dp, N.cardRingSm, shape).padding(start = 6.dp, end = if (onRemove != null) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val bmp = remember(image) { image?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.padding(vertical = 6.dp).size(32.dp).clip(RoundedCornerShape(8.dp)))
        else Box(Modifier.padding(vertical = 6.dp).size(32.dp).clip(RoundedCornerShape(8.dp)).background(N.accent800.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Description, null, tint = N.accent2, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(8.dp))
        Text(name, style = T.label.copy(color = N.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        if (onRemove != null) IconBtn(Icons.Rounded.Close, "Remove $name", tint = N.textMuted, onClick = onRemove)
    }
}
