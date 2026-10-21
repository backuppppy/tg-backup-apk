@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.tgbackup.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.tgbackup.app.App
import org.tgbackup.app.data.Account
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.JobMode
import org.tgbackup.app.data.MediaType
import org.tgbackup.app.engine.JobStatus

@Composable
fun JobsScreen() {
    val app = App.instance
    val toast = LocalToast.current
    val cfg by app.store.config.collectAsState()
    val statuses by app.jobs.statuses.collectAsState()
    var editing by remember { mutableStateOf<Job?>(null) }
    var deleting by remember { mutableStateOf<Job?>(null) }
    var resetting by remember { mutableStateOf<Job?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            val active = statuses.values.filter { it.running }
            val total = statuses.values.sumOf { it.copied }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (active.isEmpty()) "⚪ עצור · הועתקו בהרצה זו: $total"
                    else "🟢 ${active.size} משימות פעילות · הועתקו בהרצה זו: $total",
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val errors = app.jobs.startAll()
                        if (errors.isNotEmpty()) toast(errors.joinToString("\n"))
                    }) { Text("▶ הפעל הכל") }
                    OutlinedButton(enabled = active.any { !it.paused }, onClick = { app.jobs.pauseAll() }) { Text("⏸ השהה הכל") }
                    OutlinedButton(enabled = active.any { it.paused }, onClick = { app.jobs.resumeAll() }) { Text("▶ המשך הכל") }
                    OutlinedButton(enabled = active.isNotEmpty(), onClick = { app.jobs.stopAll() }) { Text("⏹ עצור הכל") }
                }
            }
        }
        if (cfg.accounts.isEmpty()) {
            item { Text("קודם הוסף חשבון בלשונית 'חשבונות'.", color = MaterialTheme.colorScheme.error) }
        }
        items(cfg.jobs, key = { it.id }) { job ->
            JobCard(
                job = job,
                status = statuses[job.id] ?: JobStatus(),
                accounts = cfg.accounts,
                onEdit = { editing = job },
                onDelete = { deleting = job },
                onReset = { resetting = job },
            )
        }
        item {
            Button(onClick = { editing = Job() }, modifier = Modifier.fillMaxWidth()) { Text("➕ הוסף משימה") }
        }
    }

    editing?.let { job ->
        val usedElsewhere = cfg.jobs.filter { it.id != job.id }.flatMap { it.accountIds }.toSet()
        JobDialog(job, cfg.accounts, usedElsewhere, onDismiss = { editing = null }) { saved ->
            app.store.updateConfig { c ->
                val exists = c.jobs.any { it.id == saved.id }
                c.copy(jobs = if (exists) c.jobs.map { if (it.id == saved.id) saved else it } else c.jobs + saved)
            }
            editing = null
        }
    }
    deleting?.let { job ->
        ConfirmDialog("למחוק את המשימה ${job.name}?", "ההתקדמות השמורה תימחק.", onDismiss = { deleting = null }) {
            app.jobs.forget(job.id)
            app.store.updateConfig { c -> c.copy(jobs = c.jobs.filter { it.id != job.id }) }
            deleting = null
        }
    }
    resetting?.let { job ->
        ConfirmDialog(
            "לאפס התקדמות?",
            "המשימה ${job.name} תתחיל מההודעה הראשונה בערוץ (ייתכנו כפילויות ביעד).",
            onDismiss = { resetting = null },
        ) {
            app.jobs.resetProgress(job.id)
            resetting = null
        }
    }
}

@Composable
private fun JobCard(
    job: Job,
    status: JobStatus,
    accounts: List<Account>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
) {
    val app = App.instance
    val toast = LocalToast.current
    val context = LocalContext.current
    val running = status.running
    val accNames = job.accountIds.mapNotNull { id -> accounts.firstOrNull { it.id == id }?.name?.ifBlank { null } }

    Card(
        Modifier.fillMaxWidth(),
        colors = if (running) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        running && status.paused -> "⏸"
                        running -> "🟢"
                        else -> "⚪"
                    } + " " + job.name.ifBlank { "משימה ${job.id}" },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text("${status.sourceTitle.ifBlank { job.source }}  ←  ${job.target}", style = MaterialTheme.typography.bodySmall)
            Text(
                "${job.mediaType.label} · ${if (job.mode == JobMode.FAST) "מהיר" else "מסודר"} · חשבונות: " +
                    accNames.ifEmpty { listOf("—") }.joinToString(),
                style = MaterialTheme.typography.bodySmall,
            )
            if (status.isProtected) Text("🔒 ערוץ מוגן — הורדה והעלאה מחדש", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            if (running) Text(status.phase, color = MaterialTheme.colorScheme.primary)
            Text("הועתקו בהרצה זו: ${status.copied}" + if (status.failed > 0) " · נכשלו: ${status.failed}" else "")
            if (status.lastId > 0) {
                val link = status.lastLink
                Text(
                    "הודעה אחרונה שטופלה: ${status.lastId}",
                    color = if (link != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (link != null) TextDecoration.Underline else null,
                    modifier = if (link != null) Modifier.clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    } else Modifier,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) {
                    Button(onClick = { app.jobs.start(job.id)?.let(toast) }) { Text("▶ הפעל") }
                } else {
                    Button(onClick = { app.jobs.stop(job.id) }) { Text("⏹ עצור") }
                    if (status.paused) {
                        OutlinedButton(onClick = { app.jobs.resume(job.id) }) { Text("▶ המשך") }
                    } else {
                        OutlinedButton(onClick = { app.jobs.pause(job.id) }) { Text("⏸ השהה") }
                    }
                }
                OutlinedButton(enabled = !running, onClick = onEdit) { Text("ערוך") }
                TextButton(enabled = !running, onClick = onReset) { Text("אפס התקדמות") }
                TextButton(enabled = !running, onClick = onDelete) { Text("מחק", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun JobDialog(initial: Job, accounts: List<Account>, usedElsewhere: Set<String>, onDismiss: () -> Unit, onSave: (Job) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var source by remember { mutableStateOf(initial.source) }
    var target by remember { mutableStateOf(initial.target) }
    var mediaType by remember { mutableStateOf(initial.mediaType) }
    var mode by remember { mutableStateOf(initial.mode) }
    var selected by remember { mutableStateOf(initial.accountIds.toSet()) }
    var startFrom by remember { mutableStateOf(if (initial.startFromId > 0) initial.startFromId.toString() else "") }
    var strip by remember { mutableStateOf(initial.stripLinks) }
    var limit by remember { mutableStateOf(initial.perMinuteLimit.toString()) }
    val valid = source.isNotBlank() && target.isNotBlank() && selected.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.source.isBlank()) "משימה חדשה" else "עריכת משימה") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("שם המשימה") }, singleLine = true)
                OutlinedTextField(source, { source = it.trim() }, label = { Text("מקור: מזהה (עם או בלי ‎-100) / @username / קישור") }, singleLine = true)
                OutlinedTextField(target, { target = it.trim() }, label = { Text("יעד: מזהה (עם או בלי ‎-100) / @username / קישור") }, singleLine = true)

                Text("סוג תוכן", style = MaterialTheme.typography.labelLarge)
                MediaType.entries.forEach { t -> RadioRow(t.label, mediaType == t) { mediaType = t } }

                Text("חשבונות (חשבון אחד — למשימה אחת בלבד)", style = MaterialTheme.typography.labelLarge)
                if (accounts.isEmpty()) Text("אין חשבונות — הוסף בלשונית 'חשבונות'", color = MaterialTheme.colorScheme.error)
                accounts.forEach { acc ->
                    val taken = acc.id in usedElsewhere && acc.id !in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !taken) {
                            selected = if (acc.id in selected) selected - acc.id else selected + acc.id
                        },
                    ) {
                        Checkbox(checked = acc.id in selected, onCheckedChange = null, enabled = !taken)
                        Text(
                            acc.name.ifBlank { acc.phone } + if (taken) " (משויך למשימה אחרת)" else "",
                            color = if (taken) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                if (selected.size > 1) {
                    Text("מצב עבודה", style = MaterialTheme.typography.labelLarge)
                    JobMode.entries.forEach { m -> RadioRow(m.label, mode == m) { mode = m } }
                }

                OutlinedTextField(
                    startFrom, { startFrom = it.filter(Char::isDigit) },
                    label = { Text("התחל מהודעה מס' (ריק = להמשיך מאיפה שעצר)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    limit, { limit = it.filter(Char::isDigit) },
                    label = { Text("תקרת הודעות לדקה לחשבון (0 = ללא)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = strip, onCheckedChange = { strip = it })
                    Text("  הסר קישורים ותיוגים מהכיתוב")
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(
                    initial.copy(
                        name = name.trim(),
                        source = source,
                        target = target,
                        mediaType = mediaType,
                        mode = mode,
                        accountIds = accounts.map { it.id }.filter { it in selected },
                        startFromId = startFrom.toLongOrNull() ?: 0,
                        stripLinks = strip,
                        perMinuteLimit = limit.toIntOrNull() ?: 25,
                    ),
                )
            }) { Text("שמור") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
