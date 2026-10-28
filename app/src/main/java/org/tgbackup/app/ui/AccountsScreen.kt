package org.tgbackup.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.tgbackup.app.App
import org.tgbackup.app.data.Account
import org.tgbackup.app.tg.AccountClient
import org.tgbackup.app.tg.AuthUi

@Composable
fun AccountsScreen() {
    val app = App.instance
    val cfg by app.store.config.collectAsState()
    val clients by app.accounts.clients.collectAsState()
    var editing by remember { mutableStateOf<Account?>(null) }
    var deleting by remember { mutableStateOf<Account?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "API ID ו-API Hash מקבלים בכתובת my.telegram.org ← API development tools.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(cfg.accounts, key = { it.id }) { acc ->
            AccountCard(acc, clients[acc.id], onEdit = { editing = acc }, onDelete = { deleting = acc })
        }
        item {
            Button(onClick = { editing = Account() }, modifier = Modifier.fillMaxWidth()) { Text("➕ הוסף חשבון") }
        }
    }

    editing?.let { acc ->
        AccountDialog(acc, onDismiss = { editing = null }) { saved ->
            app.store.updateConfig { c ->
                val exists = c.accounts.any { it.id == saved.id }
                c.copy(accounts = if (exists) c.accounts.map { if (it.id == saved.id) saved else it } else c.accounts + saved)
            }
            editing = null
        }
    }
    deleting?.let { acc ->
        ConfirmDialog("למחוק את החשבון ${acc.name}?", "החשבון יתנתק ויוסר מכל המשימות.", onDismiss = { deleting = null }) {
            deleting = null
            app.jobs.statuses.value.keys.forEach { id ->
                val job = app.store.config.value.jobs.firstOrNull { it.id == id }
                if (job != null && acc.id in job.accountIds) app.jobs.stop(id)
            }
            app.store.updateConfig { c ->
                c.copy(
                    accounts = c.accounts.filter { it.id != acc.id },
                    jobs = c.jobs.map { j -> j.copy(accountIds = j.accountIds - acc.id) },
                )
            }
            scope.launch { app.accounts.purge(acc.id) }
        }
    }
}

@Composable
private fun AccountCard(acc: Account, client: AccountClient?, onEdit: () -> Unit, onDelete: () -> Unit) {
    val app = App.instance
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val auth by (client?.auth ?: remember { MutableStateFlow<AuthUi>(AuthUi.Unsupported("חסרים API ID / Hash")) }).collectAsState()
    val error by (client?.lastError ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    val exportPrefs by app.exports.prefs.collectAsState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var exportLoggedIn by remember { mutableStateOf<Boolean?>(null) }
    var exportBusy by remember { mutableStateOf(false) }

    LaunchedEffect(acc.id, auth) {
        exportLoggedIn = if (auth is AuthUi.Ready) {
            runCatching { app.exports.isLoggedIn(acc) }.getOrDefault(false)
        } else null
    }

    fun act(block: suspend () -> String?) {
        busy = true
        scope.launch {
            val err = block()
            busy = false
            if (err != null) toast("שגיאה: $err") else input = ""
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(acc.name.ifBlank { "ללא שם" }, style = MaterialTheme.typography.titleMedium)
            Text(acc.phone, style = MaterialTheme.typography.bodySmall)

            // ── Regular (TDLib) session ──────────────────────────────────────
            val regularColor = when (auth) {
                is AuthUi.Ready -> MaterialTheme.colorScheme.secondary
                is AuthUi.Unsupported -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
            val regularText = (auth as? AuthUi.Ready)?.let { "✅ סשן רגיל: מחובר כ-${it.display}" }
                ?: "📱 סשן רגיל: ${auth.label}"
            Text(regularText, color = regularColor)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            // ── Export (MTProto) session ─────────────────────────────────────
            if (auth is AuthUi.Ready) {
                val now = (System.currentTimeMillis() / 1000).toInt()
                val availAt = exportPrefs.availableAt[acc.id] ?: 0
                val (exportText, exportColor) = when {
                    exportBusy -> "📤 סשן ייצוא: בודק…" to MaterialTheme.colorScheme.onSurface
                    exportLoggedIn == null -> "📤 סשן ייצוא: בודק…" to MaterialTheme.colorScheme.onSurface
                    exportLoggedIn == false -> "📤 סשן ייצוא: לא מחובר" to MaterialTheme.colorScheme.onSurface
                    availAt > now -> {
                        val hrs = (availAt - now) / 3600
                        val mins = ((availAt - now) % 3600) / 60
                        val when_ = if (hrs > 0) "${hrs}ש' ${mins}ד'" else "${mins}ד'"
                        "⏳ סשן ייצוא: ממתין לאישור ב-Telegram (עוד $when_)" to MaterialTheme.colorScheme.error
                    }
                    else -> "✅ סשן ייצוא: מחובר ומוכן" to MaterialTheme.colorScheme.secondary
                }
                Text(exportText, color = exportColor, style = MaterialTheme.typography.bodySmall)
            }

            // ── Auth input (existing flows) ──────────────────────────────────
            when (val a = auth) {
                AuthUi.NeedPhone -> Button(enabled = !busy && client != null, onClick = { act { client!!.sendPhone() } }) {
                    Text("התחבר (שלח קוד)")
                }
                AuthUi.NeedCode -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, label = { Text("קוד מטלגרם") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy && input.isNotBlank(), onClick = { act { client!!.sendCode(input) } }) { Text("אשר") }
                }
                is AuthUi.NeedPassword -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        label = { Text(if (a.hint.isNotBlank()) "סיסמה (רמז: ${a.hint})" else "סיסמת אימות דו-שלבי") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy && input.isNotBlank(), onClick = { act { client!!.sendPassword(input) } }) { Text("אשר") }
                }
                else -> Unit
            }

            // ── Regular session buttons ──────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("ערוך") }
                if (auth is AuthUi.Ready || auth is AuthUi.NeedCode || auth is AuthUi.NeedPassword) {
                    OutlinedButton(enabled = !busy, onClick = {
                        act { App.instance.accounts.logout(acc.id); null }
                    }) { Text("התנתק") }
                }
                TextButton(onClick = onDelete) { Text("מחק", color = MaterialTheme.colorScheme.error) }
            }

            // ── Export session buttons ───────────────────────────────────────
            if (auth is AuthUi.Ready) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        exportLoggedIn == false -> OutlinedButton(enabled = !exportBusy, onClick = {
                            exportBusy = true
                            scope.launch {
                                app.exports.login(acc)
                                    .onFailure { toast("חיבור סשן ייצוא נכשל: ${it.message}") }
                                exportLoggedIn = runCatching { app.exports.isLoggedIn(acc) }.getOrDefault(false)
                                exportBusy = false
                            }
                        }) { Text(if (exportBusy) "מחבר…" else "חבר סשן ייצוא") }

                        exportLoggedIn == true -> OutlinedButton(enabled = !exportBusy, onClick = {
                            exportBusy = true
                            scope.launch {
                                app.exports.checkTakeoutReady(acc)
                                    .onSuccess { ready ->
                                        if (ready) toast("✅ סשן הייצוא מוכן להעברה")
                                        else toast("⏳ טלגרם עדיין ממתין לאישורך — לחץ Allow בהודעת טלגרם")
                                    }
                                    .onFailure { toast("בדיקה נכשלה: ${it.message}") }
                                exportBusy = false
                            }
                        }) { Text(if (exportBusy) "בודק…" else "בדוק סשן ייצוא") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountDialog(initial: Account, onDismiss: () -> Unit, onSave: (Account) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var apiId by remember { mutableStateOf(if (initial.apiId == 0) "" else initial.apiId.toString()) }
    var apiHash by remember { mutableStateOf(initial.apiHash) }
    var phone by remember { mutableStateOf(initial.phone) }
    val valid = apiId.toIntOrNull() != null && apiHash.length >= 16 && phone.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank() && initial.apiId == 0) "חשבון חדש" else "עריכת חשבון") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("שם") }, singleLine = true)
                OutlinedTextField(
                    apiId, { apiId = it.filter(Char::isDigit) }, label = { Text("API ID") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(apiHash, { apiHash = it.trim() }, label = { Text("API Hash") }, singleLine = true)
                OutlinedTextField(
                    phone, { phone = it }, label = { Text("טלפון (+972…)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(initial.copy(name = name.trim(), apiId = apiId.toInt(), apiHash = apiHash, phone = phone.trim()))
            }) { Text("שמור") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm) { Text("אישור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}
