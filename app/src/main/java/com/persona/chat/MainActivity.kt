package com.persona.chat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import coil.*
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(b)
        val dao = AppDb.get(this).dao()
        Coil.setImageLoader(ImageLoader.Builder(this).components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }.build())
        if (Build.VERSION.SDK_INT >= 33) registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { ChatTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(dao) } } }
    }
}

@Composable fun ChatTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val cs = if (dark) darkColorScheme(primary = Color(0xFF5EB5F7), onPrimary = Color(0xFF00243C), background = Color(0xFF0E1621),
        surface = Color(0xFF17212B), surfaceVariant = Color(0xFF1E2C3A), onBackground = Color(0xFFE8EEF4), onSurface = Color(0xFFE8EEF4),
        onSurfaceVariant = Color(0xFF8FA3B5), surfaceContainerHigh = Color(0xFF17212B), surfaceContainerHighest = Color(0xFF1E2C3A))
    else lightColorScheme(primary = Color(0xFF3390EC), onPrimary = Color.White, background = Color(0xFFE7EBF0),
        surface = Color.White, surfaceVariant = Color.White, onBackground = Color(0xFF17212B), onSurface = Color(0xFF17212B),
        onSurfaceVariant = Color(0xFF6B7C8C), surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFF1F4F7))
    MaterialTheme(colorScheme = cs, shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)), content = content)
}

@Composable fun App(dao: AppDao) {
    var screen by remember { mutableStateOf(0) }
    BackHandler(screen != 0) { screen = 0 }
    AnimatedContent(screen, transitionSpec = {
        if (targetState > initialState) (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut()
        else fadeIn() togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
    }, label = "nav") { s ->
        when (s) {
            0 -> ChatScreen(dao) { screen = it }
            1 -> Page("Triggers", { screen = 0 }) { TriggerScreen(dao) }
            else -> Page("Scheduled messages", { screen = 0 }) { ScheduleScreen(dao) }
        }
    }
}

@Composable fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(4.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.weight(1f).navigationBarsPadding()) { content() }
    }
}

fun dayLabel(ts: Long): String = when {
    android.text.format.DateUtils.isToday(ts) -> "Today"
    android.text.format.DateUtils.isToday(ts + 86400000L) -> "Yesterday"
    else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ts))
}

@Composable fun ChatScreen(dao: AppDao, go: (Int) -> Unit) {
    val msgs by dao.messages().collectAsState(emptyList())
    val rules by dao.triggers().collectAsState(emptyList())
    var text by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("p", 0) }
    var botName by remember { mutableStateOf(prefs.getString("bot", "Bot") ?: "Bot") }
    var rename by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope(); val list = rememberLazyListState()
    val rows = remember(msgs) { buildList<Any> { var last = ""; msgs.forEach { m -> val d = dayLabel(m.ts); if (d != last) { add(d); last = d }; add(m) } } }
    LaunchedEffect(rows.size, typing) { if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1 + if (typing) 1 else 0) }
    fun send() {
        val t = text.trim(); if (t.isEmpty()) return; text = ""
        scope.launch {
            dao.insert(Msg(fromMe = true, text = t))
            val (r, reply) = Engine.choose(rules, t) ?: return@launch
            typing = true; delay(Engine.delayFor(r))
            dao.insert(Msg(fromMe = false, text = reply, triggerId = r.id, media = r.media.lines().filter { it.isNotBlank() }.randomOrNull()))
            typing = false
        }
    }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().imePadding()) {
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().background(cs.surface).statusBarsPadding().padding(12.dp, 8.dp, 4.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                Text(botName.take(1).uppercase(), color = cs.onPrimary, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(botName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (typing) "typing…" else "online", style = MaterialTheme.typography.bodySmall, color = cs.primary)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Triggers") }, onClick = { menu = false; go(1) })
                    DropdownMenuItem(text = { Text("Scheduled messages") }, onClick = { menu = false; go(2) })
                    DropdownMenuItem(text = { Text("Rename bot") }, onClick = { menu = false; rename = true })
                    DropdownMenuItem(text = { Text("Clear chat") }, onClick = { menu = false; confirmClear = true })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (rows.isEmpty() && !typing) Text("Say hi 👋\nReplies come from your Triggers", Modifier.align(Alignment.Center).padding(24.dp), color = cs.onSurfaceVariant)
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxHeight(), list, contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, key = { if (it is Msg) it.id else it }) { r -> if (r is Msg) Bubble(r.text, r.fromMe, r.ts, r.media) else DateChip(r as String) }
                if (typing) item { TypingBubble() }
            }
        }
        Row(Modifier.fillMaxWidth().background(cs.surface).navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            TextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, shape = RoundedCornerShape(24.dp), maxLines = 5,
                colors = TextFieldDefaults.colors(focusedContainerColor = cs.background, unfocusedContainerColor = cs.background,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { send() }, enabled = text.isNotBlank(), modifier = Modifier.size(52.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
    if (rename) { var tmp by remember { mutableStateOf(botName) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Bot name") },
            text = { OutlinedTextField(tmp, { tmp = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (tmp.isNotBlank()) { botName = tmp.trim(); prefs.edit().putString("bot", botName).apply() }; rename = false }) { Text("Save") } }) }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear whole chat?") },
        confirmButton = { TextButton(onClick = { scope.launch { dao.clearChat() }; confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

@Composable fun DateChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(label, Modifier.padding(10.dp, 3.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun bubbleColor(mine: Boolean): Color =
    if (isSystemInDarkTheme()) (if (mine) Color(0xFF2B5278) else Color(0xFF182533)) else (if (mine) Color(0xFFEFFDDE) else Color.White)

@Composable fun Bubble(t: String, mine: Boolean, ts: Long, m: String? = null) {
    val maxW = minOf(LocalConfiguration.current.screenWidthDp * 0.78f, 420f).dp
    var shown by remember { mutableStateOf(System.currentTimeMillis() - ts > 1500) }
    LaunchedEffect(Unit) { shown = true }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        AnimatedVisibility(shown, enter = fadeIn() + scaleIn(initialScale = 0.85f, transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f))) {
            Surface(shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp),
                color = bubbleColor(mine), modifier = Modifier.widthIn(max = maxW)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    if (m != null) AsyncImage(File(m), null, Modifier.widthIn(max = maxW - 20.dp).heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                    if (t.isNotEmpty()) Text(t, color = MaterialTheme.colorScheme.onSurface)
                    Row(Modifier.align(Alignment.End)) {
                        Text(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (mine) Text(" ✓✓", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable fun TypingBubble() {
    val tr = rememberInfiniteTransition(label = "typing")
    Surface(shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp), color = bubbleColor(false)) {
        Row(Modifier.padding(14.dp, 13.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                val a by tr.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "dot")
                Box(Modifier.size(7.dp).alpha(a).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
        }
    }
}

@Composable fun TriggerScreen(dao: AppDao) {
    val rules by dao.triggers().collectAsState(emptyList())
    var editing by remember { mutableStateOf<TriggerRule?>(null) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rules, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().clickable { editing = r }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
                            Text("${r.mode} · ${r.keywords.lines().joinToString(", ")} · ${r.responses.lines().count { it.isNotBlank() }} replies", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(r.enabled, { scope.launch { dao.save(r.copy(enabled = it)) } })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { editing = TriggerRule() }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("+ Trigger") }
    }
    editing?.let { init ->
        Editor(init, onSave = { scope.launch { dao.save(it) }; editing = null },
            onDelete = { scope.launch { dao.delete(init) }; editing = null }, onClose = { editing = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Editor(init: TriggerRule, onSave: (TriggerRule) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(init.name) }
    var kw by remember { mutableStateOf(init.keywords) }
    var mode by remember { mutableStateOf(init.mode) }
    var lo by remember { mutableStateOf((init.minDelayMs / 1000.0).toString()) }
    var hi by remember { mutableStateOf((init.maxDelayMs / 1000.0).toString()) }
    var pr by remember { mutableStateOf(init.priority.toString()) }
    var cs by remember { mutableStateOf(init.caseSensitive) }
    var ip by remember { mutableStateOf(init.ignorePunct) }
    var nr by remember { mutableStateOf(init.noRepeat) }
    var test by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val media = remember { mutableStateListOf<String>().apply { addAll(init.media.lines().filter { it.isNotBlank() }) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) Media.import(ctx, u)?.let { if (it !in media) media.add(it) } }
    val resp = remember { mutableStateListOf<String>().apply { addAll(init.responses.lines().filter { it.isNotBlank() }.ifEmpty { listOf("") }) } }
    fun build() = init.copy(name = name, keywords = kw, mode = mode, responses = resp.filter { it.isNotBlank() }.joinToString("\n"), media = media.joinToString("\n"),
        minDelayMs = ((lo.toDoubleOrNull() ?: 1.0) * 1000).toLong(), maxDelayMs = ((hi.toDoubleOrNull() ?: 3.0) * 1000).toLong(),
        priority = pr.toIntOrNull() ?: 0, caseSensitive = cs, ignorePunct = ip, noRepeat = nr)
    AlertDialog(onDismissRequest = onClose, title = { Text(if (init.id == 0L) "New trigger" else "Edit trigger") },
        confirmButton = { TextButton(onClick = { onSave(build()) }) { Text("Save") } },
        dismissButton = { Row { if (init.id != 0L) TextButton(onClick = onDelete) { Text("Delete") }; TextButton(onClick = onClose) { Text("Cancel") } } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(kw, { kw = it }, label = { Text("Keywords / aliases (one per line)") })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EXACT" to "Exact", "WORD" to "Whole word", "PHRASE" to "Phrase", "CONTAINS" to "Contains").forEach { (k, l) ->
                    FilterChip(mode == k, { mode = k }, label = { Text(l) }) } }
            Text("Replies (one chosen at random)", style = MaterialTheme.typography.labelLarge)
            resp.forEachIndexed { i, s -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(s, { resp[i] = it }, Modifier.weight(1f), singleLine = true)
                TextButton(onClick = { if (resp.size > 1) resp.removeAt(i) else resp[0] = "" }) { Text("−") } } }
            OutlinedButton(onClick = { resp.add("") }) { Text("+ Add reply") }
            Text("GIFs / images (one chosen at random)", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                media.forEachIndexed { i, p -> Box { AsyncImage(File(p), null, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)))
                    TextButton(onClick = { media.removeAt(i) }, modifier = Modifier.align(Alignment.TopEnd)) { Text("✕") } } } }
            OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("+ Add GIF / image") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(lo, { lo = it }, Modifier.weight(1f), label = { Text("Min delay s") }, singleLine = true)
                OutlinedTextField(hi, { hi = it }, Modifier.weight(1f), label = { Text("Max delay s") }, singleLine = true) }
            OutlinedTextField(pr, { pr = it }, label = { Text("Priority (higher wins)") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cs, { cs = it }); Text("Case-sensitive") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ip, { ip = it }); Text("Ignore punctuation") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(nr, { nr = it }); Text("Avoid repeating last reply") }
            OutlinedTextField(test, { test = it }, label = { Text("Test message") }, singleLine = true)
            if (test.isNotEmpty()) Text(if (Engine.matches(build(), test)) "✔ Matches ($mode)" else "✘ No match")
        } })
}

@Composable fun ScheduleScreen(dao: AppDao) {
    val list by dao.schedules().collectAsState(emptyList())
    var editing by remember { mutableStateOf<Schedule?>(null) }
    val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    fun save(s: Schedule) { scope.launch { val id = dao.saveSchedule(s); Scheduler.schedule(ctx, s.copy(id = if (s.id == 0L) id else s.id)) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Delivery uses Android's inexact alarms: no extra permission needed, but battery saver/Doze can delay messages by minutes. Delivery is not guaranteed.", style = MaterialTheme.typography.bodySmall) }
            items(list, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickable { editing = s }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
                            Text("%02d:%02d · %s · %s".format(s.hour, s.minute, if (s.days == 127) "Every day" else listOf("Su","Mo","Tu","We","Th","Fr","Sa").filterIndexed { i, _ -> (s.days shr i) and 1 == 1 }.joinToString(" "), s.text), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(s.enabled, { save(s.copy(enabled = it)) })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { editing = Schedule() }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("+ Schedule") }
    }
    editing?.let { init -> SchedEditor(init, { save(it); editing = null },
        { scope.launch { Scheduler.cancel(ctx, init.id); dao.deleteSchedule(init) }; editing = null }, { editing = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SchedEditor(init: Schedule, onSave: (Schedule) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(init.name) }; var text by remember { mutableStateOf(init.text) }
    var h by remember { mutableStateOf(init.hour.toString()) }; var m by remember { mutableStateOf(init.minute.toString()) }
    var days by remember { mutableStateOf(init.days) }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (init.id == 0L) "New schedule" else "Edit schedule") },
        confirmButton = { TextButton(onClick = { onSave(init.copy(name = name, text = text, hour = (h.toIntOrNull() ?: 7).coerceIn(0, 23),
            minute = (m.toIntOrNull() ?: 0).coerceIn(0, 59), days = if (days == 0) 127 else days)) }) { Text("Save") } },
        dismissButton = { Row { if (init.id != 0L) TextButton(onClick = onDelete) { Text("Delete") }; TextButton(onClick = onClose) { Text("Cancel") } } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(text, { text = it }, label = { Text("Message") })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(h, { h = it }, Modifier.weight(1f), label = { Text("Hour (0-23)") }, singleLine = true)
                OutlinedTextField(m, { m = it }, Modifier.weight(1f), label = { Text("Minute") }, singleLine = true) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Su","Mo","Tu","We","Th","Fr","Sa").forEachIndexed { i, l -> FilterChip(((days shr i) and 1) == 1, { days = days xor (1 shl i) }, label = { Text(l) }) } }
        } })
}
