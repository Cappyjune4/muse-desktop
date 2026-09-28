package com.cappyjune.muse.desktop.ui.radio

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cappyjune.muse.desktop.data.Category
import com.cappyjune.muse.desktop.data.CategoryIcon
import com.cappyjune.muse.desktop.data.Entry
import com.cappyjune.muse.desktop.data.Library
import com.cappyjune.muse.desktop.data.Radio
import com.cappyjune.muse.desktop.data.StationSources
import com.cappyjune.muse.desktop.playback.Player
import com.cappyjune.muse.desktop.ui.Artwork
import com.cappyjune.muse.desktop.ui.Back
import com.cappyjune.muse.desktop.ui.EntryRow
import com.cappyjune.muse.desktop.ui.HeaderText
import com.cappyjune.muse.desktop.ui.HoverCard
import com.cappyjune.muse.desktop.ui.Messages
import com.cappyjune.muse.desktop.ui.PageHeader
import com.cappyjune.muse.desktop.ui.SearchField
import com.cappyjune.muse.desktop.ui.SectionTitle
import com.cappyjune.muse.desktop.ui.Section
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun CategoryIcon.vector() = when (this) {
    CategoryIcon.STAR -> Icons.Rounded.Star
    CategoryIcon.GLOBE -> Icons.Rounded.Public
    CategoryIcon.NEWS -> Icons.Rounded.Newspaper
    CategoryIcon.SPORT -> Icons.Rounded.SportsSoccer
    CategoryIcon.MUSIC -> Icons.Rounded.MusicNote
    CategoryIcon.CLOCK -> Icons.Rounded.Schedule
}

/** Station rows in as many columns as the window fits. */
private val ROWS = GridCells.Adaptive(420.dp)
private val PAD = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)

/** Radio: search, the stations you played last, and muSE's categories. */
@Composable
fun RadioScreen() {
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val category = Radio.categories.firstOrNull { it.name == categoryName }
    Back.handler = { when { adding -> { adding = false; true }; category != null -> { categoryName = null; true }; query.isNotEmpty() -> { query = ""; true }; else -> false } }

    when {
        adding -> AddStationPage(onClose = { adding = false })
        category != null -> StationPage(category, onBack = { categoryName = null }, onAdd = { adding = true })
        else -> Column(Modifier.fillMaxSize()) {
            PageHeader(null, { SectionTitle(Section.RADIO) }) {
                SearchField(query, { query = it }, "Search stations", Modifier.width(360.dp))
            }
            if (query.trim().length >= 2) SearchResults(query.trim())
            else Home(onCategory = { categoryName = it.name })
        }
    }
}

@Composable
private fun Home(onCategory: (Category) -> Unit) {
    val history by Library.history.collectAsState()
    val np by Player.state.collectAsState()
    val recent = remember(history) { history.filter { it.kind == Entry.Kind.RADIO }.take(16) }
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(132.dp), state = state, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (recent.isNotEmpty()) {
                heading("Recent")
                items(recent, key = { "r-" + it.key }) { e ->
                    HoverCard(Modifier.fillMaxWidth(), onClick = { Player.playRadio(e) }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Artwork(e.art, 116.dp, corner = 24.dp)
                            Text(e.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp), color = if (np.entry?.key == e.key) MaterialTheme.colorScheme.primary else Color.Unspecified)
                        }
                    }
                }
            }
            heading("Browse")
            items(Radio.categories, key = { "c-" + it.name }) { c -> CategoryTile(c) { onCategory(c) } }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private fun LazyGridScope.heading(t: String) = item(span = { GridItemSpan(maxLineSpan) }, key = "h-$t") {
    Text(t, Modifier.padding(top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun CategoryTile(c: Category, onClick: () -> Unit) {
    HoverCard(Modifier.fillMaxWidth(), onClick) {
        Column(Modifier.fillMaxWidth().height(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp)).padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Icon(c.icon.vector(), null, tint = MaterialTheme.colorScheme.primary)
            Text(c.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Results as you type, debounced so each keystroke doesn't hit the directory. */
@Composable
private fun SearchResults(query: String) {
    var results by remember { mutableStateOf<List<Entry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        delay(400)
        results = null; failed = false
        runCatching { Radio.search(query) }.onSuccess { results = it }.onFailure { failed = true; results = emptyList() }
    }
    val r = results
    Box(Modifier.fillMaxSize()) {
        when {
            r == null -> CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
            failed -> Text("Search failed. Check your connection.", Modifier.padding(32.dp), color = MaterialTheme.colorScheme.error)
            r.isEmpty() -> Text("No stations match \"$query\".", Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Stations(r, onDelete = null)
        }
    }
}

@Composable
private fun StationPage(category: Category, onBack: () -> Unit, onAdd: () -> Unit) {
    val custom by Library.custom.collectAsState()
    var loaded by remember(category.name) { mutableStateOf<List<Entry>?>(null) }
    var error by remember(category.name) { mutableStateOf<String?>(null) }
    val isCustom = category.kind == "custom"
    if (!isCustom) LaunchedEffect(category.name) {
        runCatching { Radio.stations(category) }.onSuccess { loaded = it }.onFailure { error = "Couldn't load stations: ${it.message}" }
    }
    val stations = if (isCustom) custom else loaded
    Column(Modifier.fillMaxSize()) {
        PageHeader(onBack, { HeaderText(category.name) }) {
            if (isCustom) FilledTonalButton(onClick = onAdd) { Icon(Icons.Rounded.Add, null); Text("  Add a station") }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                error != null -> Text(error!!, Modifier.padding(32.dp), color = MaterialTheme.colorScheme.error)
                stations == null -> CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
                stations.isEmpty() -> Text(if (isCustom) "No stations yet. Add one: search for it, or paste its stream address." else "No stations found.",
                    Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Stations(stations, onDelete = if (isCustom) Library::removeCustom else null)
            }
        }
    }
}

@Composable
private fun Stations(list: List<Entry>, onDelete: ((Entry) -> Unit)?) {
    val favs by Library.favourites.collectAsState()
    val np by Player.state.collectAsState()
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(ROWS, state = state, contentPadding = PAD) {
            items(list, key = { it.key }) { e ->
                val fav = favs.any { it.key == e.key }
                val c = MaterialTheme.colorScheme
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalContextMenuRepresentation provides
                    androidx.compose.foundation.DefaultContextMenuRepresentation(c.surfaceContainerHigh, c.onSurface, c.primary.copy(alpha = 0.16f))) {
                androidx.compose.foundation.ContextMenuArea(items = { listOf(
                    androidx.compose.foundation.ContextMenuItem("Play") { Player.playRadio(e) },
                    androidx.compose.foundation.ContextMenuItem("Copy share link") { com.cappyjune.muse.desktop.data.Share.copy(e); Messages.show(com.cappyjune.muse.desktop.ui.copiedMessage()) },
                ) }) {
                EntryRow(e, playing = np.entry?.key == e.key, onClick = { Player.playRadio(e) }) {
                    if (onDelete != null) IconButton(onClick = { onDelete(e) }) { Icon(Icons.Rounded.Delete, "Remove station") }
                    IconButton(onClick = { Library.toggleFavourite(e) }) {
                        Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (fav) "Remove favourite" else "Add favourite",
                            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * Search radio-browser, TuneIn and the GitHub community list at once. Click a result to hear
 * it, + to keep it in My Stations. A stream address can be added by hand too.
 */
@Composable
private fun AddStationPage(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val custom by Library.custom.collectAsState()
    val np by Player.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<StationSources.Group>?>(emptyList()) }
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { groups = emptyList(); return@LaunchedEffect }
        delay(500)
        groups = null
        groups = StationSources.searchAll(q)
    }
    fun resolveThen(e: Entry, action: (Entry) -> Unit) = scope.launch {
        val r = runCatching { StationSources.resolve(e) }.getOrNull()
        if (r == null) Messages.show("That station won't play outside TuneIn's own app.") else action(r)
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(onClose, { HeaderText("Add a station") }) {
            SearchField(query, { query = it }, "Search radio-browser, TuneIn, GitHub", Modifier.width(420.dp))
            TextButton(onClick = { manual = true }) { Icon(Icons.Rounded.Link, null); Text("  Stream address") }
        }
        val g = groups
        val state = rememberLazyGridState()
        Box(Modifier.fillMaxSize()) {
            when {
                g == null -> CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
                g.isEmpty() -> Text("Type a station's name. The first GitHub search downloads a 3 MB list, then it's kept for a week.",
                    Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyVerticalGrid(ROWS, state = state, contentPadding = PAD) {
                    g.forEach { group ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h-" + group.source) {
                            Text("${group.source}  ·  ${group.error?.let { "unavailable" } ?: group.results.size}", Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        items(group.results, key = { group.source + "|" + it.id }) { e ->
                            val saved = custom.any { it.title == e.title && (it.key == e.key || it.id == e.id) }
                            EntryRow(e, playing = np.entry?.id == e.id, onClick = { resolveThen(e) { Player.playRadio(it) } }) {
                                IconButton(enabled = !saved, onClick = { resolveThen(e) { Library.addCustom(it); Messages.show("Added ${it.title} to My Stations") } }) {
                                    Icon(if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                                        if (saved) "Already in My Stations" else "Add to My Stations", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
    if (manual) AddStationDialog(onDismiss = { manual = false }) { n, u, l -> Library.addCustom(n, u, l); manual = false; Messages.show("Added $n to My Stations") }
}

@Composable
private fun AddStationDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var logo by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Rounded.Radio, null) }, title = { Text("Add a station") },
        text = {
            Column(Modifier.widthIn(min = 420.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Stream address (http…)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(logo, { logo = it }, label = { Text("Logo address (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, url, logo) }, enabled = valid) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

