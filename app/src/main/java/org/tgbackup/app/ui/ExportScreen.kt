@file:OptIn(ExperimentalMaterial3Api::class)

package org.tgbackup.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.tgbackup.app.App
import org.tgbackup.app.data.Account
import org.tgbackup.app.export.RunningExport
import org.tgbackup.app.tg.AuthUi
import org.tgbackup.export.ChatEntry
import org.tgbackup.export.ExportState
import org.tgbackup.export.MediaSettings
import org.tgbackup.export.ProcessingState
import org.tgbackup.export.TopicEntry
import org.tgbackup.export.data.DialogInfo
import org.tgbackup.export.data.FormatSizeText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val kMegabyte = 1024L * 1024
private const val kSizeValueCount = 100

/** Desktop's SizeLimitByIndex for the 100-step size slider. */
private fun SizeLimitByIndex(i: Int): Long {
    val index = i + 1
    val megabytes = when {
        index <= 10 -> index
        index <= 30 -> 10 + (index - 10) * 2
        index <= 40 -> 50 + (index - 30) * 5
        index <= 60 -> 100 + (index - 40) * 10
        index <= 70 -> 300 + (index - 60) * 20
        index <= 80 -> 500 + (index - 70) * 50
        index <= 90 -> 1000 + (index - 80) * 100
        else -> 2000 + (index - 90) * 200
    }
    return megabytes * kMegabyte
}

private fun IndexBySizeLimit(limit: Long): Int {
    for (i in 0 until kSizeValueCount) if (SizeLimitByIndex(i) >= limit) return i
    return kSizeValueCount - 1
}

private fun formatTime(t: Int, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t.toLong() * 1000))

private fun localCalendar(t: Int): Calendar = Calendar.getInstance().apply { timeInMillis = t.toLong() * 1000 }

/** Local midnight of the given local date. */
private fun localDayStart(year: Int, month: Int, day: Int): Int =
    (Calendar.getInstance().apply { clear(); set(year, month, day, 0, 0, 0) }.timeInMillis / 1000).toInt()

private fun DialogInfo.Type.label(): String = when (this) {
    DialogInfo.Type.Self -> "הודעות שמורות"
    DialogInfo.Type.Replies, DialogInfo.Type.VerifyCodes, DialogInfo.Type.Personal -> "צ'אט פרטי"
    DialogInfo.Type.Bot -> "בוט"
    DialogInfo.Type.PrivateGroup, DialogInfo.Type.PrivateSupergroup -> "קבוצה פרטית"
    DialogInfo.Type.PublicSupergroup -> "קבוצה ציבורית"
    DialogInfo.Type.PrivateChannel -> "ערוץ פרטי"
    DialogInfo.Type.PublicChannel -> "ערוץ ציבורי"
    DialogInfo.Type.Unknown -> ""
}

@Composable
fun ExportScreen() {
    val app = App.instance
    val running by app.exports.running.collectAsState()
    val current = running
    if (current != null) {
        ProgressPanel(current)
    } else {
        ExportSetup()
    }
}

@Composable
private fun ExportSetup() {
    val app = App.instance
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val cfg by app.store.config.collectAsState()
    val clients by app.accounts.clients.collectAsState()
    val prefs by app.exports.prefs.collectAsState()

    var accountId by remember { mutableStateOf<String?>(null) }
    val account: Account? = cfg.accounts.firstOrNull { it.id == accountId }
    var loggedIn by remember(accountId) { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var chats by remember(accountId) { mutableStateOf<List<ChatEntry>?>(null) }
    var search by remember { mutableStateOf("") }
    var chat by remember(accountId) { mutableStateOf<ChatEntry?>(null) }
    var topics by remember { mutableStateOf<List<TopicEntry>?>(null) }
    var topic by remember { mutableStateOf<TopicEntry?>(null) }
    var from by remember { mutableIntStateOf(0) }
    var till by remember { mutableIntStateOf(0) }

    LaunchedEffect(accountId) {
        val acc = account ?: return@LaunchedEffect
        loggedIn = app.exports.isLoggedIn(acc)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ייצוא היסטוריית צ'אט", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "ייצוא ערוץ, קבוצה או צ'אט בדיוק כמו \"Export chat history\" של טלגרם דסקטופ (HTML / JSON), " +
                            "דרך Takeout session.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("חשבון:", style = MaterialTheme.typography.labelLarge)
                    for (acc in cfg.accounts) {
                        val auth by (clients[acc.id]?.auth ?: remember { MutableStateFlow<AuthUi>(AuthUi.Closed) }).collectAsState()
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = acc.id == accountId, onClick = { accountId = acc.id }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = acc.id == accountId, onClick = { accountId = acc.id })
                            Text(acc.name.ifBlank { acc.phone } + if (auth is AuthUi.Ready) "" else " (לא מחובר)")
                        }
                    }
                }
            }
        }
        if (account != null) {
            item {
                SessionCard(
                    account = account,
                    loggedIn = loggedIn,
                    busy = busy,
                    availableAt = prefs.availableAt[account.id] ?: 0,
                    onLogin = {
                        busy = "מתחבר…"
                        scope.launch {
                            val r = app.exports.login(account)
                            busy = null
                            r.onFailure { toast("ההתחברות נכשלה: ${it.message}") }
                            loggedIn = app.exports.isLoggedIn(account)
                        }
                    },
                )
            }
        }
        if (account != null && loggedIn == true) {
            if (chat == null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = busy == null, onClick = {
                            busy = "טוען צ'אטים…"
                            scope.launch {
                                val r = runCatching { app.exports.loadChats(account) { n -> busy = "טוען צ'אטים… $n" } }
                                busy = null
                                r.onSuccess { chats = it }.onFailure { toast("טעינת הצ'אטים נכשלה: ${it.message}") }
                            }
                        }) { Text(if (chats == null) "טען צ'אטים" else "רענן") }
                        busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                chats?.let { list ->
                    item {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            label = { Text("חיפוש") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val filtered = list.filter {
                        search.isBlank() || it.title.contains(search, ignoreCase = true) || it.username.contains(search, ignoreCase = true)
                    }
                    items(filtered, key = { it.peerId }) { entry ->
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                chat = entry
                                topic = null
                                topics = null
                                from = 0
                                till = 0
                                if (entry.isForum) {
                                    scope.launch {
                                        topics = runCatching { app.exports.loadTopics(account, entry) }.getOrElse {
                                            toast("טעינת הנושאים נכשלה: ${it.message}")
                                            emptyList()
                                        }
                                    }
                                }
                            },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(
                                        entry.type.label(),
                                        entry.username.takeIf { it.isNotBlank() }?.let { "@$it" },
                                        if (entry.isForum) "פורום" else null,
                                        if (entry.archived) "בארכיון" else null,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    ExportOptions(
                        chat = chat!!,
                        topics = topics,
                        topic = topic,
                        onTopic = { topic = it },
                        from = from,
                        till = till,
                        onFrom = { from = it },
                        onTill = { till = it },
                        onBack = { chat = null },
                        onStart = {
                            val selected = chat!!
                            app.exports.start(account, selected, topic, from, till) { app.startBackupService() }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(account: Account, loggedIn: Boolean?, busy: String?, availableAt: Int, onLogin: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("סשן ייצוא", style = MaterialTheme.typography.titleSmall)
            when (loggedIn) {
                null -> Text("בודק…")
                true -> Text("✅ מחובר (סשן נפרד מ-TG Backup)", color = MaterialTheme.colorScheme.secondary)
                false -> {
                    Text(
                        "הייצוא משתמש בסשן נפרד, כמו טלגרם דסקטופ. ההתחברות מאושרת אוטומטית דרך החשבון " +
                            "${account.name.ifBlank { account.phone }} שמחובר ב-TG Backup (QR).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(enabled = busy == null, onClick = onLogin) { Text(busy ?: "התחבר לסשן הייצוא") }
                }
            }
            val now = (System.currentTimeMillis() / 1000).toInt()
            if (availableAt > now) {
                val whenText = formatTime(availableAt, "dd.MM.yyyy HH:mm")
                Text(
                    "⏳ מטעמי אבטחה טלגרם יאפשר להתחיל את הייצוא רק בעוד ${(availableAt - now) / 3600} שעות (ב-$whenText). " +
                        "נשלחה הודעה לכל המכשירים שלך. חזור אז ונסה שוב מאותו מכשיר.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ExportOptions(
    chat: ChatEntry,
    topics: List<TopicEntry>?,
    topic: TopicEntry?,
    onTopic: (TopicEntry?) -> Unit,
    from: Int,
    till: Int,
    onFrom: (Int) -> Unit,
    onTill: (Int) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val app = App.instance
    val context = LocalContext.current
    val prefs by app.exports.prefs.collectAsState()
    var storageOk by remember { mutableStateOf(StorageAccess.granted(context)) }
    LifecycleResumeEffect(Unit) {
        storageOk = StorageAccess.granted(context)
        onPauseOrDispose { }
    }
    var picking by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("החלף צ'אט") }
            }
            if (chat.isForum) {
                Text("נושא (Topic export settings):", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().selectable(topic == null) { onTopic(null) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = topic == null, onClick = { onTopic(null) })
                    Text("כל הצ'אט")
                }
                if (topics == null) Text("טוען נושאים…", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    for (t in topics.orEmpty()) {
                        Row(Modifier.fillMaxWidth().selectable(topic?.id == t.id) { onTopic(t) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = topic?.id == t.id, onClick = { onTopic(t) })
                            Text(t.title)
                        }
                    }
                }
                HorizontalDivider()
            }

            Text("Media export settings", style = MaterialTheme.typography.labelLarge)
            val options = listOf(
                "Photos (תמונות)" to MediaSettings.Type.Photo,
                "Videos (סרטונים)" to MediaSettings.Type.Video,
                "Voice messages (הודעות קוליות)" to MediaSettings.Type.VoiceMessage,
                "Video messages (הודעות וידאו)" to MediaSettings.Type.VideoMessage,
                "Stickers (מדבקות)" to MediaSettings.Type.Sticker,
                "GIFs" to MediaSettings.Type.GIF,
                "Files (קבצים)" to MediaSettings.Type.File,
            )
            for ((label, type) in options) {
                val checked = (prefs.mediaTypes and type) == type
                Row(
                    Modifier.fillMaxWidth().clickable {
                        app.exports.updatePrefs { p -> p.copy(mediaTypes = if (checked) p.mediaTypes and type.inv() else p.mediaTypes or type) }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { on ->
                        app.exports.updatePrefs { p -> p.copy(mediaTypes = if (on) p.mediaTypes or type else p.mediaTypes and type.inv()) }
                    })
                    Text(label)
                }
            }
            Text("Size limit: ${prefs.sizeLimit / kMegabyte} MB")
            Slider(
                value = IndexBySizeLimit(prefs.sizeLimit).toFloat(),
                onValueChange = { v -> app.exports.updatePrefs { it.copy(sizeLimit = SizeLimitByIndex(v.toInt())) } },
                valueRange = 0f..(kSizeValueCount - 1).toFloat(),
                steps = kSizeValueCount - 2,
            )
            HorizontalDivider()

            Text("Format", style = MaterialTheme.typography.labelLarge)
            for ((label, value) in listOf("Human-readable HTML" to "Html", "Machine-readable JSON" to "Json", "Both" to "HtmlAndJson")) {
                Row(
                    Modifier.fillMaxWidth().selectable(prefs.format == value) { app.exports.updatePrefs { it.copy(format = value) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = prefs.format == value, onClick = { app.exports.updatePrefs { it.copy(format = value) } })
                    Text(label)
                }
            }
            OutlinedTextField(
                value = prefs.path,
                onValueChange = { v -> app.exports.updatePrefs { it.copy(path = v) } },
                label = { Text("Download path") },
                placeholder = { Text(app.exports.defaultPath()) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()

            fun shown(t: Int) = formatTime(t, "d MMMM yyyy") + ", " + formatTime(t, "HH:mm")
            Text("From: " + (if (from != 0) shown(from) else "the oldest message"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { picking = "from" }) { Text("תאריך") }
                if (from != 0) OutlinedButton(onClick = { picking = "fromTime" }) { Text("שעה") }
                if (from != 0) TextButton(onClick = { onFrom(0) }) { Text("Reset") }
            }
            Text("To: " + (if (till != 0) shown(till) else "present"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { picking = "till" }) { Text("תאריך") }
                if (till != 0) OutlinedButton(onClick = { picking = "tillTime" }) { Text("שעה") }
                if (till != 0) TextButton(onClick = { onTill(0) }) { Text("Reset") }
            }
            HorizontalDivider()

            if (!storageOk) {
                Text("כדי לשמור את הייצוא בתיקיית ההורדות צריך הרשאת גישה לקבצים.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { StorageAccess.request(context) }) { Text("אפשר גישה לקבצים") }
            }
            Button(enabled = storageOk, onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Export") }
        }
    }

    // Desktop: dates start at local midnight; times keep a 10 minute gap.
    val kOffset = 600
    fun removeTime(t: Int): Int = local0(t)
    when (picking) {
        "from", "till" -> {
            val isFrom = picking == "from"
            val current = if (isFrom) from else till
            val initial = localCalendar(if (current != 0) current else (System.currentTimeMillis() / 1000).toInt())
            val utcMillis = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            }.timeInMillis
            val state = rememberDatePickerState(initialSelectedDateMillis = utcMillis)
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        val millis = state.selectedDateMillis
                        if (millis != null) {
                            val date = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
                            val limit = localDayStart(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH))
                            if (isFrom) {
                                onFrom(limit)
                            } else {
                                onTill(if (limit <= from && from != 0) from + kOffset else limit)
                            }
                        }
                        picking = null
                    }) { Text("שמור") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("ביטול") } },
            ) { DatePicker(state = state) }
        }
        "fromTime", "tillTime" -> {
            val isFrom = picking == "fromTime"
            val current = if (isFrom) from else till
            val t = localCalendar(current)
            val state = rememberTimePickerState(initialHour = t.get(Calendar.HOUR_OF_DAY), initialMinute = t.get(Calendar.MINUTE), is24Hour = true)
            AlertDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        val seconds = state.hour * 3600 + state.minute * 60
                        val result = seconds + removeTime(current)
                        if (isFrom) {
                            onFrom(if (result >= till && till != 0) till - kOffset else result)
                        } else {
                            onTill(if (result <= from && from != 0) from + kOffset else result)
                        }
                        picking = null
                    }) { Text("שמור") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("ביטול") } },
                text = { TimePicker(state = state) },
            )
        }
    }
}

/** base::unixtime::serialize(QDateTime(date, QTime())): local midnight of that day. */
private fun local0(t: Int): Int {
    val c = localCalendar(t)
    return localDayStart(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
}

@Composable
private fun ProgressPanel(running: RunningExport) {
    val app = App.instance
    val context = LocalContext.current
    val state by running.controller.state.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val s = state) {
                    is ExportState.Idle -> Text("Initializing...")
                    is ExportState.Processing -> {
                        Text("Exporting your data", style = MaterialTheme.typography.titleMedium)
                        ProcessingRows(s.state)
                        Text(
                            "אפשר לסגור את המסך. הייצוא ממשיך ברקע עם התראה קבועה.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.state.bytesCount > 0) OutlinedButton(onClick = { app.exports.skipFile() }) { Text("Skip this file") }
                            Button(onClick = { confirmStop = true }) { Text("Stop") }
                        }
                    }
                    is ExportState.Finished -> {
                        Text("Data export completed.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                        Text("Total files: ${s.filesCount}")
                        Text("Total size: ${FormatSizeText(s.bytesCount)}")
                        Text(s.path, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { openExport(context, s.path) }) { Text("Show my data") }
                            OutlinedButton(onClick = { app.exports.dismiss() }) { Text("סגור") }
                        }
                    }
                    is ExportState.ApiError -> {
                        val text = when {
                            s.type == "TAKEOUT_INVALID" -> "Sorry, you started a new data export, so this data export has been canceled."
                            s.type.startsWith("TAKEOUT_INIT_DELAY_") -> {
                                val seconds = maxOf(s.type.removePrefix("TAKEOUT_INIT_DELAY_").toIntOrNull() ?: 1, 1)
                                val hours = seconds / 3600
                                val hoursText = if (hours <= 0) "less than an hour" else "$hours hours"
                                val whenText = formatTime((System.currentTimeMillis() / 1000).toInt() + seconds, "d MMMM yyyy 'at' HH:mm")
                                "For security reasons, you will be able to begin downloading your data in $hoursText. " +
                                    "We have notified all your devices about the export request to make sure it's authorized and give you time to react if it's not.\n\n" +
                                    "Please come back on $whenText and repeat the request using the same device."
                            }
                            else -> "API Error happened :(\n${s.code}: ${s.type}"
                        }
                        Text(text, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { app.exports.dismiss() }) { Text("סגור") }
                    }
                    is ExportState.OutputError -> {
                        Text("Could not write to ${s.path}", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { app.exports.dismiss() }) { Text("סגור") }
                    }
                    is ExportState.Cancelled -> {
                        Text("הייצוא הופסק.")
                        OutlinedButton(onClick = { app.exports.dismiss() }) { Text("סגור") }
                    }
                }
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            text = { Text("Are you sure you want to stop exporting your data?\n\nIf you do, you'll need to start over.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; app.exports.stop() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("ביטול") } },
        )
    }
}

/** ContentFromState of export_view_content.cpp. */
@Composable
private fun ProcessingRows(state: ProcessingState) {
    @Composable
    fun row(label: String, info: String, progress: Float) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(info, style = MaterialTheme.typography.bodySmall)
            }
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
    fun mainProgress(): Float {
        if (state.substepsTotal == 0) return 0f
        val done = state.substepsPassed / state.substepsTotal.toFloat()
        fun addPart(index: Int, count: Int) =
            if (count > 0) (state.substepsNow.toFloat() * index) / (state.substepsTotal.toFloat() * count) else 0f
        val add = if (state.entityCount == 1 && state.entityIndex == 0) addPart(state.itemIndex, state.itemCount)
        else addPart(state.entityIndex, state.entityCount)
        return done + add
    }
    val mainInfo = if (state.entityCount > 0) "${state.entityIndex + 1} / ${state.entityCount}" else ""
    when (state.step) {
        ProcessingState.Step.Initializing -> row("Initializing...", mainInfo, mainProgress())
        ProcessingState.Step.DialogsList -> row("Processing chats...", mainInfo, mainProgress())
        ProcessingState.Step.Dialogs, ProcessingState.Step.Topic -> {
            if (state.step == ProcessingState.Step.Topic || state.entityCount > 1) row("Chats", mainInfo, mainProgress())
            val name = when {
                state.entityName.isEmpty() -> "Deleted"
                state.entityType == ProcessingState.EntityType.SavedMessages -> "Saved Messages"
                state.entityType == ProcessingState.EntityType.RepliesMessages -> "Replies"
                else -> state.entityName
            }
            row(
                name,
                if (state.itemCount > 0) "${state.itemIndex} / ${state.itemCount}" else "",
                if (state.itemCount > 0) state.itemIndex / state.itemCount.toFloat() else 0f,
            )
        }
        else -> row(state.step.name, mainInfo, mainProgress())
    }
    if (state.bytesCount > 0) {
        row(state.bytesName, FormatDownload(state.bytesLoaded, state.bytesCount), state.bytesLoaded / state.bytesCount.toFloat())
    }
}

/** Ui::FormatDownloadText: "{ready} / {total} {mb}". */
private fun FormatDownload(ready: Long, total: Long): String = when {
    total >= 1024 * 1024 -> {
        val r = ready * 10 / (1024 * 1024)
        val t = total * 10 / (1024 * 1024)
        "${r / 10}.${r % 10} / ${t / 10}.${t % 10} MB"
    }
    total >= 1024 -> "${ready / 1024} / ${total / 1024} KB"
    else -> "$ready / $total B"
}

private fun openExport(context: android.content.Context, path: String) {
    runCatching {
        val file = java.io.File(path)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (path.endsWith(".json")) "application/json" else "text/html")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

object StorageAccess {
    fun granted(context: android.content.Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            android.os.Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun request(context: android.content.Context) {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            }.onFailure {
                context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            (context as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        }
    }
}

/** Asks for the 2FA password of an export session; shown over every tab, since copy jobs log in too. */
@Composable
fun ExportPasswordDialog() {
    val password by App.instance.exports.password.collectAsState()
    password?.let { request ->
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { request.answer.complete(null) },
            title = { Text("סיסמת אימות דו-שלבי") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("סשן הייצוא צריך את הסיסמה של החשבון." + if (request.hint.isNotBlank()) "\nרמז: ${request.hint}" else "")
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { request.answer.complete(value) }) { Text("אישור") } },
            dismissButton = { TextButton(onClick = { request.answer.complete(null) }) { Text("ביטול") } },
        )
    }
}
