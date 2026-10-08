@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.kiln.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- shared

/** Small / Medium / Large: one knob for height, padding and text size. */
enum class KSize(private val h: Int, private val pad: Int, private val txt: Int, private val ic: Int) {
    Small(34, 12, 13, 16), Medium(44, 18, 15, 18), Large(54, 24, 17, 22);
    // Scaled by the theme's density and text scale (KilnTheme `density`, `textScale`).
    val height: Dp get() = (h * Nocturne.sizeScale).dp
    val padH: Dp get() = (pad * Nocturne.sizeScale).dp
    val text: Int get() = Math.round(txt * Nocturne.textScale)
    val icon: Dp get() = (ic * Nocturne.sizeScale).dp
}

/** How strongly a button or card is drawn. */
enum class KVariant { Filled, Tonal, Outline, Ghost }

/** Width / height only when given: every K component takes them. */
fun Modifier.kSize(width: Dp? = null, height: Dp? = null): Modifier =
    this.then(if (width != null) Modifier.width(width) else Modifier).then(if (height != null) Modifier.height(height) else Modifier)

/**
 * One-off colours for a single component, over the theme: `colors = KColors(container = Color(0xFF2E7D32))`.
 * Leave a field null to keep the theme's colour. For an app-wide change use [KilnTheme] instead.
 */
data class KColors(val container: Color? = null, val content: Color? = null, val border: Color? = null)

/** Background / content colours for a tone at a variant. */
internal fun toneColors(tone: KTone, variant: KVariant): Pair<Color, Color> {
    val c = tone.color()
    val onFilled = if (Nocturne.isDark) Nocturne.bg else Color.White
    // On glass the pane sits over an accent gradient: accent text would sink into it, so lift it.
    val ink = if (Nocturne.surfaceStyle == KStyle.Glass) androidx.compose.ui.graphics.lerp(c, if (Nocturne.isDark) Color.White else Color.Black, 0.45f) else c
    return when (variant) {
        KVariant.Filled -> (if (tone == KTone.Neutral) Nocturne.surfaceHi else c) to (if (tone == KTone.Neutral) Nocturne.text else onFilled)
        KVariant.Tonal -> c.copy(alpha = 0.18f) to (if (tone == KTone.Neutral) Nocturne.text else ink)
        KVariant.Outline, KVariant.Ghost -> Color.Transparent to (if (tone == KTone.Neutral) Nocturne.text else ink)
    }
}

/** [toneColors] with a component's [KColors] applied; the third value is the border (null = none). */
internal fun resolveColors(tone: KTone, variant: KVariant, colors: KColors?): Triple<Color, Color, Color?> {
    val (bg, fg) = toneColors(tone, variant)
    val content = colors?.content ?: fg
    val border = colors?.border ?: if (variant == KVariant.Outline) content.copy(alpha = 0.6f) else null
    return Triple(colors?.container ?: bg, content, border)
}

// ---------------------------------------------------------------- buttons

/**
 * The button. `KButton("Save") { … }`; `KButton("Delete", tone = KTone.Danger, variant = KVariant.Outline)`;
 * `KButton("Next", trailingIcon = Icons.Filled.ArrowForward, fullWidth = true, size = KSize.Large)`.
 * [loading] shows a spinner and ignores taps.
 */
@Composable
fun KButton(
    text: String,
    modifier: Modifier = Modifier,
    variant: KVariant = KVariant.Filled,
    tone: KTone = KTone.Accent,
    size: KSize = KSize.Medium,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    fullWidth: Boolean = false,
    width: Dp? = null,
    height: Dp? = null,
    corner: Dp = kr(12),
    colors: KColors? = null,
    textStyle: TextStyle? = null,
    border: Dp = 1.dp,
    contentPadding: PaddingValues? = null,
    onClick: () -> Unit,
) {
    val (bg, fg, line) = resolveColors(tone, variant, colors)
    val shape = RoundedCornerShape(corner)
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .kSize(width, height ?: size.height).heightIn(min = size.height)
            .kSurface(shape, bg.copy(alpha = bg.alpha * alpha), if (line != null && border > 0.dp) line.copy(alpha = line.alpha * alpha) else null,
                neutral = variant == KVariant.Ghost || (variant == KVariant.Outline && colors?.container == null), borderWidth = border)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(contentPadding ?: PaddingValues(horizontal = size.padH)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) { CircularProgressIndicator(Modifier.size(size.icon), color = fg, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        else if (icon != null) { Icon(icon, null, tint = fg.copy(alpha = alpha), modifier = Modifier.size(size.icon)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg.copy(alpha = alpha), maxLines = 1,
            style = (textStyle ?: TextStyle(fontSize = size.text.sp, fontWeight = FontWeight.Medium)).let { st ->
                st.copy(fontFamily = st.fontFamily ?: Nocturne.sans) })
        if (trailingIcon != null) { Spacer(Modifier.width(8.dp)); Icon(trailingIcon, null, tint = fg.copy(alpha = alpha), modifier = Modifier.size(size.icon)) }
    }
}

/**
 * An icon in the theme's colours: `KIcon(Icons.Filled.Savings, "Savings", tone = KTone.Ok, size = KSize.Large)`.
 * [description] is read by TalkBack; pass null for purely decorative icons. [color] overrides the tone.
 */
@Composable
fun KIcon(icon: ImageVector, description: String?, modifier: Modifier = Modifier, tone: KTone = KTone.Neutral,
          size: KSize = KSize.Medium, color: Color? = null) =
    Icon(icon, description, modifier.size(size.icon + 4.dp), tint = color ?: if (tone == KTone.Neutral) Nocturne.text else tone.color())

/** Icon-only button; [description] is read by TalkBack (always give one). */
@Composable
fun KIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    variant: KVariant = KVariant.Ghost,
    tone: KTone = KTone.Neutral,
    size: KSize = KSize.Medium,
    enabled: Boolean = true,
    colors: KColors? = null,
    onClick: () -> Unit,
) {
    val (bg, fg, line) = resolveColors(tone, variant, colors)
    Box(
        modifier.size(size.height).clip(CircleShape).background(bg)
            .then(if (line != null) Modifier.border(1.dp, line, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = fg.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(size.icon + 4.dp)) }
}

/** Floating action button; with [text] it becomes the extended (icon + label) kind. */
@Composable
fun KFab(icon: ImageVector, description: String, modifier: Modifier = Modifier, text: String? = null,
         tone: KTone = KTone.Accent, onClick: () -> Unit) {
    val c = tone.color()
    if (text == null) FloatingActionButton(onClick, modifier, containerColor = c, contentColor = Nocturne.bg) { Icon(icon, description) }
    else ExtendedFloatingActionButton(text = { Text(text) }, icon = { Icon(icon, description) }, onClick = onClick,
        modifier = modifier, containerColor = c, contentColor = Nocturne.bg)
}

/** Segmented control: one of [options] selected. Also the "button group" / "toggle group (single)". */
@Composable
fun KSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: KSize = KSize.Medium,
    fullWidth: Boolean = true,
    icons: List<ImageVector?> = emptyList(),
) {
    Row(modifier.then(if (fullWidth) Modifier.fillMaxWidth() else Modifier).height(size.height)
        .kSurface(RoundedCornerShape(kr(12)), Nocturne.surface, Nocturne.divider).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Row(Modifier.then(if (fullWidth) Modifier.weight(1f) else Modifier).fillMaxWidth().clip(RoundedCornerShape(kr(9)))
                .background(if (on) Nocturne.accent800 else Color.Transparent).clickable { onSelect(i) }
                .padding(horizontal = 12.dp).height(size.height), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                icons.getOrNull(i)?.let { Icon(it, null, tint = if (on) Nocturne.accent100 else Nocturne.textLabel, modifier = Modifier.size(size.icon)); Spacer(Modifier.width(6.dp)) }
                Text(o, color = if (on) Nocturne.accent100 else Nocturne.textLabel, fontSize = size.text.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Toggle group (multi): any number of [options] on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KToggleGroup(options: List<String>, selected: Set<Int>, onChange: (Set<Int>) -> Unit, modifier: Modifier = Modifier,
                 size: KSize = KSize.Small) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val on = i in selected
            KButton(o, variant = if (on) KVariant.Tonal else KVariant.Outline, tone = if (on) KTone.Accent else KTone.Neutral, size = size,
                icon = if (on) Icons.Filled.Check else null) { onChange(if (on) selected - i else selected + i) }
        }
    }
}

// ---------------------------------------------------------------- text input

/**
 * Text field with label, placeholder, icons, helper/error text, counter, password and number modes.
 * `KTextField(name, { name = it }, label = "Name")`;
 * `KTextField(pin, { pin = it }, label = "PIN", password = true, keyboard = KeyboardType.NumberPassword, maxLength = 6)`.
 */
@Composable
fun KTextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingClick: (() -> Unit)? = null,
    helper: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    maxLength: Int? = null,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    width: Dp? = null,
    height: Dp? = null,
    corner: Dp = kr(12),
    colors: KColors? = null,
    textStyle: TextStyle? = null,
) {
    var reveal by remember { mutableStateOf(false) }
    Column(modifier.then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))) {
        OutlinedTextField(
            value = value,
            onValueChange = { v -> if (maxLength == null || v.length <= maxLength) onChange(v) },
            modifier = Modifier.fillMaxWidth().kSize(height = height),
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon?.let { { Icon(it, null) } },
            trailingIcon = when {
                password -> { { IconButton({ reveal = !reveal }) { Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    if (reveal) "Hide" else "Show") } } }
                trailingIcon != null -> { { if (onTrailingClick != null) IconButton(onTrailingClick) { Icon(trailingIcon, null) } else Icon(trailingIcon, null) } }
                else -> null
            },
            isError = error != null,
            singleLine = singleLine, minLines = minLines, maxLines = maxLines,
            enabled = enabled, readOnly = readOnly,
            visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password && keyboard == KeyboardType.Text) KeyboardType.Password else keyboard),
            shape = RoundedCornerShape(corner),
            textStyle = textStyle ?: MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors?.border ?: Nocturne.accent,
                unfocusedBorderColor = colors?.border?.copy(alpha = 0.6f) ?: Nocturne.neutral700,
                cursorColor = colors?.border ?: Nocturne.accent, focusedLabelColor = colors?.border ?: Nocturne.accent,
                focusedContainerColor = colors?.container ?: Color.Transparent, unfocusedContainerColor = colors?.container ?: Color.Transparent,
                focusedTextColor = colors?.content ?: Nocturne.text, unfocusedTextColor = colors?.content ?: Nocturne.text),
        )
        val note = error ?: helper
        if (note != null || maxLength != null) Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, end = 4.dp)) {
            Text(note.orEmpty(), color = if (error != null) Nocturne.danger else Nocturne.textMuted, fontSize = kt(12), modifier = Modifier.weight(1f))
            if (maxLength != null) Text("${value.length}/$maxLength", color = Nocturne.textMuted, fontSize = kt(12))
        }
    }
}

/** Multi-line text area (notes, descriptions). */
@Composable
fun KTextArea(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null,
              placeholder: String? = null, minLines: Int = 4, maxLines: Int = 10, maxLength: Int? = null, height: Dp? = null) =
    KTextField(value, onChange, modifier, label = label, placeholder = placeholder, singleLine = false,
        minLines = minLines, maxLines = maxLines, maxLength = maxLength, height = height)

/** Search bar with a clear button; [onSubmit] runs on the keyboard's search action. */
@Composable
fun KSearchBar(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "Search",
               trailing: (@Composable () -> Unit)? = null, height: Dp = ks(48), colors: KColors? = null) {
    Row(modifier.fillMaxWidth().height(height).kSurface(RoundedCornerShape(50), colors?.container ?: Nocturne.surface, colors?.border ?: Nocturne.divider).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Search, null, tint = Nocturne.textMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, color = Nocturne.textMuted, fontSize = kt(15))
            BasicTextField(query, onQuery, singleLine = true, cursorBrush = SolidColor(Nocturne.accent),
                textStyle = TextStyle(color = Nocturne.text, fontSize = kt(15)), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) IconButton({ onQuery("") }, Modifier.size(32.dp)) { Icon(Icons.Filled.Clear, "Clear search", tint = Nocturne.textMuted) }
        trailing?.invoke()
    }
}

/** One-time code: [length] boxes, digits only. */
@Composable
fun KOtpField(code: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, length: Int = 6, boxSize: Dp = ks(48)) {
    val focus = remember { FocusRequester() }
    Box(modifier) {
        BasicTextField(code, { v -> val d = v.filter(Char::isDigit).take(length); onChange(d) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.focusRequester(focus).size(1.dp), textStyle = TextStyle(color = Color.Transparent))
        Row(Modifier.clickable { focus.requestFocus() }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(length) { i ->
                val active = i == code.length
                Box(Modifier.size(boxSize).clip(RoundedCornerShape(kr(10))).background(Nocturne.surface)
                    .border(if (active) 2.dp else 1.dp, if (active) Nocturne.accent else Nocturne.neutral700, RoundedCornerShape(kr(10))),
                    contentAlignment = Alignment.Center) {
                    Text(code.getOrNull(i)?.toString() ?: "", color = Nocturne.text, fontSize = kt(20), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** − value + with bounds and step (quantities, servings, counts). */
@Composable
fun KStepper(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, min: Int = 0, max: Int = 99, step: Int = 1,
             label: String? = null, size: KSize = KSize.Medium) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        KIconButton(Icons.Filled.Remove, "Decrease", variant = KVariant.Tonal, size = size, enabled = value - step >= min) { onChange((value - step).coerceAtLeast(min)) }
        Text("$value", fontSize = (size.text + 3).sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.width(size.height))
        KIconButton(Icons.Filled.Add, "Increase", variant = KVariant.Tonal, size = size, enabled = value + step <= max) { onChange((value + step).coerceAtMost(max)) }
    }
}

// ---------------------------------------------------------------- selection

/** Dropdown select: tap to pick one of [options]. */
@Composable
fun KSelect(options: List<String>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
            label: String? = null, placeholder: String = "Choose…", width: Dp? = null, enabled: Boolean = true, colors: KColors? = null) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))) {
        Column {
            if (label != null) Text(label, color = Nocturne.textLabel, fontSize = kt(12), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            Row(Modifier.fillMaxWidth().height(ks(52)).kSurface(RoundedCornerShape(kr(12)), colors?.container ?: Nocturne.surface, colors?.border ?: if (open) Nocturne.accent else Nocturne.neutral700)
                .clickable(enabled = enabled) { open = true }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected?.let { options.getOrNull(it) } ?: placeholder, color = if (selected == null) Nocturne.textMuted else Nocturne.text,
                    fontSize = kt(15), modifier = Modifier.weight(1f), maxLines = 1)
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Nocturne.textMuted)
            }
        }
        DropdownMenu(open, { open = false }, containerColor = Nocturne.surfaceHi) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) },
                    trailingIcon = if (i == selected) { { Icon(Icons.Filled.Check, null, tint = Nocturne.accent) } } else null)
            }
        }
    }
}

/** Combobox: a select you can type into to filter long lists. */
@Composable
fun KCombobox(options: List<String>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier,
              label: String? = null, placeholder: String = "Type to search…", maxShown: Int = 8) {
    var query by remember(selected) { mutableStateOf(selected ?: "") }
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        KTextField(query, { query = it; open = true }, label = label, placeholder = placeholder, trailingIcon = Icons.Filled.KeyboardArrowDown,
            onTrailingClick = { open = !open })
        DropdownMenu(open, { open = false }, containerColor = Nocturne.surfaceHi) {
            options.filter { it.contains(query, ignoreCase = true) || query == selected }.take(maxShown).forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; query = o; onSelect(o) })
            }
        }
    }
}

/** Checkbox with its label (tap either). */
@Composable
fun KCheckbox(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier,
              subtitle: String? = null, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(kr(8))).clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Nocturne.accent, checkmarkColor = Nocturne.bg))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Nocturne.textMuted)
        }
    }
}

/** Radio group: one of [options]. */
@Composable
fun KRadioGroup(options: List<String>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        options.forEachIndexed { i, o ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(kr(8))).clickable { onSelect(i) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(i == selected, { onSelect(i) }, colors = RadioButtonDefaults.colors(selectedColor = Nocturne.accent))
                Text(o, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Bare switch (for your own row layouts; [KSwitchRow] is the labelled one). */
@Composable
fun KSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    Switch(checked, onChange, modifier, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Nocturne.accent,
        checkedThumbColor = Nocturne.bg, uncheckedTrackColor = Nocturne.surfaceHi, uncheckedBorderColor = Nocturne.divider))

/** Filter chips: single ([multi] = false) or multiple choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KChipGroup(options: List<String>, selected: Set<Int>, onChange: (Set<Int>) -> Unit, modifier: Modifier = Modifier,
               multi: Boolean = true) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, o ->
            val on = i in selected
            FilterChip(on, { onChange(if (multi) (if (on) selected - i else selected + i) else setOf(i)) }, label = { Text(o) },
                leadingIcon = if (on) { { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) } } else null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Nocturne.accent800, selectedLabelColor = Nocturne.accent100,
                    selectedLeadingIconColor = Nocturne.accent100))
        }
    }
}

/** Slider with a label and the current value. */
@Composable
fun KSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, range: ClosedFloatingPointRange<Float> = 0f..100f,
            steps: Int = 0, label: String? = null, format: (Float) -> String = { "%.0f".format(it) }, width: Dp? = null) {
    Column(modifier.then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))) {
        if (label != null) Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodyMedium, color = Nocturne.accent2)
        }
        Slider(value, onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Nocturne.accent, activeTrackColor = Nocturne.accent, inactiveTrackColor = Nocturne.surfaceHi))
    }
}

/** Two-thumb range slider (price from–to, time window). */
@Composable
fun KRangeSlider(value: ClosedFloatingPointRange<Float>, onChange: (ClosedFloatingPointRange<Float>) -> Unit, modifier: Modifier = Modifier,
                 range: ClosedFloatingPointRange<Float> = 0f..100f, steps: Int = 0, label: String? = null,
                 format: (Float) -> String = { "%.0f".format(it) }) {
    Column(modifier.fillMaxWidth()) {
        if (label != null) Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${format(value.start)} – ${format(value.endInclusive)}", style = MaterialTheme.typography.bodyMedium, color = Nocturne.accent2)
        }
        RangeSlider(value, onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Nocturne.accent, activeTrackColor = Nocturne.accent, inactiveTrackColor = Nocturne.surfaceHi))
    }
}

/** Star rating; [onChange] null makes it display-only. */
@Composable
fun KRating(value: Int, onChange: ((Int) -> Unit)? = null, modifier: Modifier = Modifier, max: Int = 5, starSize: Dp = ks(28),
            tone: KTone = KTone.Warn) {
    Row(modifier) {
        for (i in 1..max) Icon(if (i <= value) Icons.Filled.Star else Icons.Filled.StarBorder, "$i of $max",
            tint = if (i <= value) tone.color() else Nocturne.neutral600,
            modifier = Modifier.size(starSize).then(if (onChange != null) Modifier.clickable { onChange(if (i == value) 0 else i) } else Modifier))
    }
}

/** Date field: shows [value], opens a calendar picker; like every input, `value` + `onChange`. */
@Composable
fun KDateField(value: LocalDate?, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, label: String = "Date",
               format: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")) {
    var open by remember { mutableStateOf(false) }
    KPickerBox(label, value?.format(format) ?: "Pick a date", value == null, modifier) { open = true }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton({ state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; open = false }) { Text("OK") } },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } }) { DatePicker(state) }
    }
}

/** Time field: shows [value], opens a picker. */
@Composable
fun KTimeField(value: LocalTime?, onChange: (LocalTime) -> Unit, modifier: Modifier = Modifier, label: String = "Time", is24h: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    KPickerBox(label, value?.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a")) ?: "Pick a time", value == null, modifier) { open = true }
    if (open) {
        val state = rememberTimePickerState(value?.hour ?: 9, value?.minute ?: 0, is24h)
        KDialog(open = true, title = label, onDismiss = { open = false }, confirmLabel = "OK",
            onConfirm = { onChange(LocalTime.of(state.hour, state.minute)) }) { TimeInput(state) }
    }
}

@Composable
private fun KPickerBox(label: String, text: String, empty: Boolean, modifier: Modifier, onClick: () -> Unit) =
    Column(modifier.fillMaxWidth()) {
        Text(label, color = Nocturne.textLabel, fontSize = kt(12), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth().height(ks(52)).kSurface(RoundedCornerShape(kr(12)), Nocturne.surface, Nocturne.neutral700).clickable(onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = if (empty) Nocturne.textMuted else Nocturne.text, fontSize = kt(15), modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Nocturne.textMuted)
        }
    }

/** A label above any custom input and an error/helper below it (KTextField already has its own label). */
@Composable
fun KLabeled(label: String, modifier: Modifier = Modifier, error: String? = null, helper: String? = null, content: @Composable () -> Unit) =
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Nocturne.textLabel, fontSize = kt(12), modifier = Modifier.padding(start = 4.dp))
        content()
        (error ?: helper)?.let { Text(it, color = if (error != null) Nocturne.danger else Nocturne.textMuted, fontSize = kt(12), modifier = Modifier.padding(start = 4.dp)) }
    }
