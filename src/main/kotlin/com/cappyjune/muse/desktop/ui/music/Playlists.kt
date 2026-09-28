package com.cappyjune.muse.desktop.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cappyjune.muse.desktop.data.JfItem
import com.cappyjune.muse.desktop.data.Jellyfin
import kotlinx.coroutines.launch

/**
 * Add songs to a Jellyfin playlist, or start a new one with them. Playlists live on the server,
 * so they show up in every Jellyfin app. [createOnly] skips straight to naming (Save queue).
 */
@Composable
fun AddToPlaylistDialog(itemIds: List<String>, onDismiss: () -> Unit, createOnly: Boolean = false, suggestedName: String = "") {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<JfItem>?>(null) }
    var naming by remember { mutableStateOf(createOnly) }
    var name by remember { mutableStateOf(suggestedName) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!createOnly) playlists = runCatching { Jellyfin.playlists() }.getOrDefault(emptyList()) }
    val count = if (itemIds.size == 1) "1 song" else "${itemIds.size} songs"

    fun done(msg: String) { com.cappyjune.muse.desktop.ui.Messages.show(msg); onDismiss() }

    if (naming) AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (createOnly) "Save as playlist" else "New playlist") },
        text = {
            Column {
                Text("$count, saved to your Jellyfin server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { Jellyfin.createPlaylist(name.trim(), itemIds) }
                        .onSuccess { done("Created “${name.trim()}”") }
                        .onFailure { busy = false; com.cappyjune.muse.desktop.ui.Messages.show("Couldn't create it: ${it.message}") }
                }
            }) { Text(if (busy) "Saving…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) else AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $count to…") },
        text = {
            val p = playlists
            if (p == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { naming = true },
                        leadingContent = { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("New playlist") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                items(p, key = { it.Id }) { pl ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !busy) {
                            busy = true
                            scope.launch {
                                runCatching { Jellyfin.addToPlaylist(pl.Id, itemIds) }
                                    .onSuccess { done("Added to ${pl.Name}") }
                                    .onFailure { busy = false; com.cappyjune.muse.desktop.ui.Messages.show("Couldn't add: ${it.message}") }
                            }
                        },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                        headlineContent = { Text(pl.Name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
