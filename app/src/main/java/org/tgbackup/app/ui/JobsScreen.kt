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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.tgbackup.app.App
import org.tgbackup.app.data.Account
import org.tgbackup.app.data.Job
import org.tgbackup.app.data.JobMode
import org.tgbackup.app.data.MediaType
import org.tgbackup.app.engine.contentLabel
import org.tgbackup.app.engine.copiesNothing
import org.tgbackup.app.engine.legacyMediaType
import org.tgbackup.app.engine.normalizeTypes
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
    val screenScope = rememberCoroutineScope()
    // A job shown without its source's latest id asks for it once (the "מתוך Y" of its card).
    LaunchedEffect(cfg.jobs.map { it.id }) {
        cfg.jobs.filter { (app.jobs.statuses.value[it.id]?.latestId ?: 0L) <= 0L }.forEach { j ->
            launch { runCatching { app.jobs.refreshLatest(j.id) } }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            val active = statuses.values.filter { it.running }
            val total = formatCount(statuses.values.sumOf { it.copiedTotal })
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (active.isEmpty()) "⚪ עצור · הועתקו: $total"
                    else "🟢 ${active.size} משימות פעילות · הועתקו: $total",
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
            // As on the desktop: a new source starts the job over (position and run totals), then its latest id is read.
            val old = cfg.jobs.firstOrNull { it.id == saved.id }
            if (old != null && old.source != saved.source) {
                app.jobs.resetProgress(saved.id)
                screenScope.launch { runCatching { app.jobs.refreshLatest(saved.id) } }
            }
            app.store.updateConfig { c ->
                val exists = c.jobs.any { it.id == saved.id }
                c.copy(jobs = if (exists) c.jobs.map { if (it.id == saved.id) saved else it } else c.jobs + saved)
            }
            editing = null
        }
    }
    deleting?.let { job ->
        ConfirmDialog("למחוק את המשימה ${job.name}?", "ההתקדמות השמורה תימחק.", onDismiss = { deleting = null }) {
            // The config first: the engine saves a latest id only for a job still in it (as the desktop does).
            app.store.updateConfig { c -> c.copy(jobs = c.jobs.filter { it.id != job.id }) }
            app.jobs.forget(job.id)
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
                "${job.contentLabel()} · ${if (job.mode == JobMode.FAST) "מהיר" else "מסודר"}${if (job.viaTakeout) " · 📡 בלי הורדה" else ""} · חשבונות: " +
                    accNames.ifEmpty { listOf("—") }.joinToString(),
                style = MaterialTheme.typography.bodySmall,
            )
            if (job.viaTakeout) Text("📡 קריאה דרך סשן הייצוא ושליחה בלי הורדה", style = MaterialTheme.typography.bodySmall)
            else if (status.isProtected) Text("🔒 ערוץ מוגן — הורדה והעלאה מחדש", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            // The desktop's card: every message number opens that message in Telegram.
            if (running) {
                LinkText(phaseText(status.phase), status.lastTargetLink, MaterialTheme.colorScheme.primary)
            }
            if (status.lastId > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("הודעה אחרונה שטופלה מהמקור: ")
                    LinkText(status.lastId.toString(), status.lastLink)
                }
            }
            Text("הועתקו: ${formatCount(status.copiedTotal)}" + if (status.failed > 0) " · נכשלו: ${formatCount(status.failed.toLong())}" else "")
            if (status.lastId > 0 || status.latestId > 0) {
                FlowRow(verticalArrangement = Arrangement.Center) {
                    Text("אוחז במקור ב-id ")
                    LinkText(formatCount(status.lastId), status.lastLink)
                    if (status.latestId > 0) {
                        Text(" מתוך ")
                        LinkText(formatCount(status.latestId), status.latestLink)
                    }
                }
            }
            if (runTimeShown(status)) {
                // Ticks once a second only while the clock runs.
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                if (status.runningSince > 0) {
                    LaunchedEffect(status.runningSince) {
                        while (true) {
                            now = System.currentTimeMillis()
                            delay(1_000)
                        }
                    }
                }
                Text("⏱ רץ: ${formatRunTime(runMillisAt(status, now))}")
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
    var chosen by remember { mutableStateOf(editorChoices(initial)) }
    // An old job saved untouched keeps its old type ("כל המדיה" also takes polls and locations, which no box covers).
    val oldChoices = remember { if (initial.mediaTypes == null) editorChoices(initial) else null }
    var extensions by remember { mutableStateOf(initial.extensions) }
    var mode by remember { mutableStateOf(initial.mode) }
    var selected by remember { mutableStateOf(initial.accountIds.toSet()) }
    var startFrom by remember { mutableStateOf(if (initial.startFromId > 0) initial.startFromId.toString() else "") }
    var strip by remember { mutableStateOf(initial.stripLinks) }
    var skipArabic by remember { mutableStateOf(initial.skipArabic) }
    var limit by remember { mutableStateOf(initial.perMinuteLimit.toString()) }
    var viaTakeout by remember { mutableStateOf(initial.viaTakeout) }
    val all = MediaType.ALL in chosen
    val copiesNothing = copiesNothing(chosen.toList(), if (all) "" else extensions)
    val valid = source.isNotBlank() && target.isNotBlank() && selected.isNotEmpty() && !copiesNothing

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
                CONTENT_CHOICES.forEach { (t, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            // "הכל" clears every other choice, and any other choice clears "הכל".
                            chosen = when {
                                t in chosen -> chosen - t
                                t == MediaType.ALL -> setOf(MediaType.ALL)
                                else -> chosen - MediaType.ALL + t
                            }
                        },
                    ) {
                        Checkbox(checked = t in chosen, onCheckedChange = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    extensions, { extensions = it },
                    label = { Text("סיומות קבצים (לא חובה), למשל pdf, zip") }, singleLine = true, enabled = !all,
                )
                if (!all) Text("קבצים עם הסיומות האלה יגובו בנוסף לסוגים שסימנת", style = MaterialTheme.typography.bodySmall)
                if (copiesNothing) Text("בחר לפחות סוג תוכן אחד או סיומת", color = MaterialTheme.colorScheme.error)

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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = viaTakeout, onCheckedChange = { viaTakeout = it })
                    Text("  📡 העתקה בלי הורדה (דרך סשן הייצוא)")
                }
                if (viaTakeout) {
                    Text(
                        "עובד גם בערוצים מוגנים וחסומים. " +
                            "בפעם הראשונה טלגרם עשוי לבקש לאשר את הייצוא בהודעת שירות.",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                if (limitWarns(limit)) Text(LIMIT_WARNING, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = strip, onCheckedChange = { strip = it })
                    Text("  הסר קישורים ותיוגים מהכיתוב")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = skipArabic, onCheckedChange = { skipArabic = it })
                    Text("  דלג על הודעות בערבית")
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
                        mediaTypes = if (chosen == oldChoices && extensions.isBlank()) null else normalizeTypes(chosen.toList()),
                        mediaType = if (chosen == oldChoices && extensions.isBlank()) initial.mediaType else legacyMediaType(normalizeTypes(chosen.toList())),
                        extensions = if (all) "" else extensions.trim(),
                        mode = mode,
                        accountIds = accounts.map { it.id }.filter { it in selected },
                        startFromId = startFrom.toLongOrNull() ?: 0,
                        stripLinks = strip,
                        skipArabic = skipArabic,
                        perMinuteLimit = limit.toIntOrNull() ?: 25,
                        viaTakeout = viaTakeout,
                    ),
                )
            }) { Text("שמור") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

/** The editor's content choices (multi-select); "הכל" excludes the others. */
private val CONTENT_CHOICES = listOf(
    MediaType.ALL to "הכל",
    MediaType.PHOTO to "תמונות",
    MediaType.VIDEO to "סרטונים",
    MediaType.FILE to "קבצים",
    MediaType.MUSIC to "מוזיקה",
    MediaType.VOICE to "הודעות קוליות",
    MediaType.TEXT to "טקסט",
)

/** A job saved before the multi-select opens with the boxes that mean the same. */
private fun editorChoices(job: Job): Set<MediaType> = job.mediaTypes?.toSet() ?: when (job.mediaType) {
    MediaType.VIDEO_DOC -> setOf(MediaType.VIDEO, MediaType.FILE, MediaType.MUSIC, MediaType.VOICE)
    MediaType.ALL_MEDIA -> setOf(MediaType.PHOTO, MediaType.VIDEO, MediaType.FILE, MediaType.MUSIC, MediaType.VOICE)
    else -> setOf(job.mediaType)
}

private const val LIMIT_WARNING = "מומלץ 25 (לכל היותר 30). מספר גבוה יותר עלול לגרום לטלגרם להגביל את החשבון או למחוק אותו."

/** Above 30, or 0 (no limit); an empty field saves as 25, so no warning. */
private fun limitWarns(s: String): Boolean = s.toIntOrNull()?.let { it == 0 || it > 30 } ?: false

/** A text that opens [link] in Telegram when tapped; plain text without a link. */
@Composable
private fun LinkText(text: String, link: String?, plainColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    val context = LocalContext.current
    Text(
        text,
        color = if (link != null) MaterialTheme.colorScheme.primary else plainColor,
        textDecoration = if (link != null) TextDecoration.Underline else null,
        modifier = if (link != null) Modifier.clickable {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        } else Modifier,
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
