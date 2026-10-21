package org.tgbackup.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.g000sha256.tdl.TdlClient
import org.tgbackup.app.App
import org.tgbackup.app.BuildConfig
import org.tgbackup.app.engine.Level

@Composable
fun LogScreen() {
    val entries by App.instance.log.entries.collectAsState()
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { App.instance.log.clear() }) { Text("נקה") }
            Text("${entries.size} שורות", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries.asReversed(), key = { it.seq }) { e ->
                val color = when (e.level) {
                    Level.SUCCESS -> Color(0xFF7FD1AE)
                    Level.WARN -> Color(0xFFFFCB6B)
                    Level.ERROR -> Color(0xFFF07178)
                    Level.INFO -> Color(0xFFB0BEC5)
                }
                Text("${e.time}  ${e.msg}", color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val pm = context.getSystemService(PowerManager::class.java)
    var ignoring by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("עבודה ברקע", style = MaterialTheme.typography.titleMedium)
                Text(
                    "בזמן שמשימה רצה מוצגת התראה קבועה — זה מה שמונע מאנדרואיד לסגור את האפליקציה. " +
                        "כדי שזה יעבוד גם עם מסך כבוי לאורך זמן, כדאי לפטור את האפליקציה מחיסכון בסוללה.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (ignoring) {
                    Text("✅ האפליקציה פטורה מחיסכון בסוללה", color = MaterialTheme.colorScheme.secondary)
                } else {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }) { Text("פטור מחיסכון בסוללה") }
                }
                Text(
                    "ב-Xiaomi / Samsung / Huawei יש גם הגדרת 'הפעלה אוטומטית' או 'אפליקציות ישנות' — כדאי לאשר שם את TG Backup.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("אודות", style = MaterialTheme.typography.titleMedium)
                Text("גרסה ${BuildConfig.VERSION_NAME} · TDLib ${TdlClient.TDL_VERSION}", style = MaterialTheme.typography.bodySmall)
                LinkButton("קבוצת טלגרם", "https://t.me/backuppppy")
                LinkButton("GitHub", "https://github.com/backuppppy/tg-backup-apk")
            }
        }
    }
}

@Composable
private fun LinkButton(label: String, url: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) {
        Text(label)
    }
}
