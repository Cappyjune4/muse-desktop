package com.cappyjune.muse.desktop.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cappyjune.muse.desktop.data.Jellyfin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sign in with a password, or Quick Connect: approve a code from another signed-in device. */
@Composable
fun SignInScreen() {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var qc by remember { mutableStateOf<Jellyfin.QuickConnect?>(null) }

    // While a Quick Connect code is showing, poll until it's approved elsewhere.
    qc?.let { code ->
        LaunchedEffect(code.Secret) {
            while (true) {
                delay(3000)
                val ok = runCatching { Jellyfin.quickConnectApproved(server, code.Secret) }.getOrDefault(false)
                if (ok) {
                    runCatching { Jellyfin.quickConnectFinish(server, code.Secret) }
                        .onFailure { error = it.message; qc = null }
                    break
                }
            }
        }
    }

    // A card in the middle of the window, wide enough to type into and no wider.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
        Modifier.widthIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        com.cappyjune.muse.desktop.ui.MuseLogo(big = true)
        Spacer(Modifier.height(20.dp))
        com.cappyjune.muse.desktop.ui.JellyfinLogo(72.dp, Modifier.padding(bottom = 12.dp))
        Text("Sign in to Jellyfin", style = MaterialTheme.typography.headlineSmall)
        Text("Each person uses their own account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(server, { server = it }, label = { Text("Server") }, placeholder = { Text("https://jellyfin.example.com") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())

        val code = qc
        if (code != null) {
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Quick Connect code", style = MaterialTheme.typography.labelLarge)
                    Text(code.Code, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Text("In Jellyfin on another device: profile → Quick Connect → enter this code.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(Modifier.height(24.dp))
                    TextButton(onClick = { qc = null }) { Text("Cancel") }
                }
            }
        } else {
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !busy && user.isNotBlank(),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        runCatching { Jellyfin.signIn(server, user, pass) }.onFailure { error = it.message }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Signing in…" else "Sign in") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    error = null
                    scope.launch { runCatching { Jellyfin.quickConnectStart(server) }.onSuccess { qc = it }.onFailure { error = it.message } }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use Quick Connect instead") }
        }
        error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Text("Away from home, you'll need your VPN on to reach the server.", Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    }
}
