package com.cappyjune.muse.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.cappyjune.muse.desktop.data.Entry
import org.xml.sax.InputSource

/** A short message at the bottom of the window (the desktop's toast); MuseApp shows it. */
object Messages {
    var current by mutableStateOf<String?>(null)
    fun show(text: String) { current = text }
}

/** The four muSE colours (m u S E), text-safe for the current light/dark surface. */
@Composable
fun museColours(): List<Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return (if (dark) listOf(0xFF4DA6F5, 0xFF66BB6A, 0xFFFFB74D, 0xFFEF7061)
    else listOf(0xFF315ABE, 0xFF2E7D32, 0xFFE08E0B, 0xFFC0392B)).map { Color(it) }
}

/** The muSE wordmark, one colour per letter, as on the phone and the muOS theme. */
@Composable
fun MuseLogo(big: Boolean = false) {
    val colours = museColours()
    Text(
        buildAnnotatedString { "muSE".forEachIndexed { i, ch -> withStyle(SpanStyle(color = colours[i])) { append(ch) } } },
        style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold,
    )
}

/** An Android vector drawable from resources/drawable, as the phone app uses them. */
fun vector(name: String): ImageVector =
    Thread.currentThread().contextClassLoader.getResourceAsStream("drawable/$name.xml")!!.use { loadXmlImageVector(InputSource(it), Density(1f)) }

private val jellyfin by lazy { vector("ic_jellyfin_color") }

@Composable
fun JellyfinLogo(size: Dp = 24.dp, modifier: Modifier = Modifier) = Image(rememberVectorPainter(jellyfin), "Jellyfin", modifier.size(size))

/** Rounded artwork with a tinted placeholder icon, on every row and the player. */
@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier, fallback: ImageVector = Icons.Rounded.Radio, corner: Dp = 12.dp) {
    val shape = RoundedCornerShape(corner)
    val placeholder = @Composable {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(fallback, null, Modifier.size(size * 0.45f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    Box(modifier.size(size).clip(shape)) {
        if (url.isNullOrBlank()) placeholder()
        else SubcomposeAsyncImage(url, null, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentScale = ContentScale.Crop, loading = { placeholder() }, error = { placeholder() }, filterQuality = FilterQuality.High)
    }
}

/** A card that lifts a little under the mouse, with a hand cursor. */
@Composable
fun HoverCard(modifier: Modifier, onClick: () -> Unit, content: @Composable (hovered: Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val s by animateFloatAsState(if (hovered) 1.04f else 1f, label = "lift")
    Column(modifier.scale(s).hoverable(source).clickable(source, indication = null, onClick = onClick)
        .pointerHoverIcon(PointerIcon.Hand)) { content(hovered) }
}

/** A station, song or episode as a row: artwork, name, what it is; highlighted while it plays. */
@Composable
fun EntryRow(e: Entry, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (hovered) cs.surfaceContainerHigh else Color.Transparent)
        .hoverable(source).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Artwork(e.art, 52.dp)
            if (playing) Icon(Icons.Rounded.GraphicEq, "Playing", Modifier.align(Alignment.Center).size(30.dp)
                .background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(4.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) cs.primary else Color.Unspecified)
            if (e.subtitle.isNotBlank()) Text(e.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** The pill search field used across sections. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    TextField(value, onValueChange, modifier, singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(Icons.Rounded.Close, "Clear") } },
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ))
}

/** The top of a page: back (for a page opened from a section), its title, and actions on the right. */
@Composable
fun PageHeader(back: (() -> Unit)?, title: @Composable () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (back == null) 32.dp else 20.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (back != null) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }; Spacer(Modifier.width(8.dp)) }
        Box(Modifier.weight(1f)) { title() }
        actions()
    }
}

@Composable
fun HeaderText(text: String) =
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Escape goes back a page; whichever section is showing sets this. */
object Back {
    var handler: (() -> Boolean)? = null
    fun back(): Boolean = handler?.invoke() ?: false
}
