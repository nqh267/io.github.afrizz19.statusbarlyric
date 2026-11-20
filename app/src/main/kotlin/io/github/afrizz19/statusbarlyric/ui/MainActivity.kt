package io.github.afrizz19.statusbarlyric.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.afrizz19.statusbarlyric.BuildConfig
import io.github.afrizz19.statusbarlyric.api.LrcMuxApiClient
import io.github.afrizz19.statusbarlyric.prefs.LyricPrefs
import io.github.afrizz19.statusbarlyric.service.LiveCaptionAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen()
                }
            }
        }
    }
}

private data class AppEntry(
    val label: String,
    val packageName: String,
)

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val prefs = remember { LyricPrefs.of(context) }
    var style by remember { mutableStateOf(LyricPrefs.style(prefs)) }
    var targetPackages by remember { mutableStateOf(LyricPrefs.targetPackages(prefs)) }
    var showAppPicker by remember { mutableStateOf(false) }

    fun update(next: LyricPrefs.Style) {
        style = next
        LyricPrefs.save(prefs, next)
    }

    fun updateTargets(next: Set<String>) {
        targetPackages = next
        LyricPrefs.setTargetPackages(prefs, next)
    }

    if (showAppPicker) {
        TargetAppsDialog(
            selected = targetPackages,
            onDismiss = { showAppPicker = false },
            onSave = {
                updateTargets(it)
                showAppPicker = false
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()

        if (!LyricPrefs.sharedWithHook) InactiveWarning()

        SectionLabel("Preview")
        StylePreview(style)

        SectionLabel("Apps & Fallback")
        TargetAppsCard(
            selectedPackages = targetPackages,
            onOpenPicker = { showAppPicker = true },
        )
        LiveCaptionCard()

        SectionLabel("Settings")
        SyncSetting(style) { update(it) }
        ModeSetting(style) { update(it) }
        ColorSetting(style) { update(it) }
        ShadowSetting(style) { update(it) }
        OutlineSetting(style) { update(it) }

        SectionLabel("Setup")
        Step(
            1,
            "Enable in LSPosed",
            "Open LSPosed → Modules → enable 'Status Bar Lyric', and tick System UI in its scope.",
        )
        Step(
            2,
            "Reboot (or restart SystemUI)",
            "The hook is installed next to the status bar clock without interfering with Dynamic Island.",
        )
        Step(
            3,
            "Play music or video",
            "Any whitelisted app (Spotify, YouTube, TikTok, etc.). Falls back to LRCLIB and Live Caption when needed.",
        )

        Card(modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Lyrics Sources", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. Lrcmux (${LrcMuxApiClient.DEFAULT_BASE_URL})\n" +
                        "2. LRCLIB (https://lrclib.net/api/get)\n" +
                        "3. Android Live Caption (com.google.android.as after 2.5s)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Footer()
    }
}

@Composable
private fun TargetAppsCard(
    selectedPackages: Set<String>,
    onOpenPicker: () -> Unit,
) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Target Apps (Whitelist)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${selectedPackages.size} app(s) allowed to display lyrics",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onOpenPicker) {
                    Text("Select Apps")
                }
            }
            if (selectedPackages.isNotEmpty()) {
                Text(
                    selectedPackages.sorted().joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun TargetAppsDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(selected) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            val map = linkedMapOf<String, AppEntry>()
            for (ri in resolved) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                if (!map.containsKey(pkg)) {
                    val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                    map[pkg] = AppEntry(label = label, packageName = pkg)
                }
            }
            for (pkg in selected) {
                if (!map.containsKey(pkg)) {
                    val label = runCatching {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(ai).toString()
                    }.getOrDefault(pkg)
                    map[pkg] = AppEntry(label = label, packageName = pkg)
                }
            }
            map.values.sortedWith(
                compareByDescending<AppEntry> { it.packageName in selected }
                    .thenBy { it.label.lowercase() }
            )
        }
        loading = false
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Target Apps (${draft.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search app name or package...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { draft = LyricPrefs.DEFAULT_PACKAGES }) {
                        Text("Reset defaults")
                    }
                    TextButton(onClick = { draft = emptySet() }) {
                        Text("Clear all")
                    }
                }
                if (loading) {
                    Text("Loading installed apps...", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = app.packageName in draft
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        draft = if (checked) draft - app.packageName else draft + app.packageName
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        draft = if (isChecked) draft + app.packageName else draft - app.packageName
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        app.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun LiveCaptionCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = isAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live Caption Fallback", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (enabled) "Accessibility Service: Active"
                        else "Accessibility Service: Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                ) {
                    Text(if (enabled) "Settings" else "Enable")
                }
            }
            Text(
                "Reads Android Live Caption (com.google.android.as) when a whitelisted app has no synced lyrics after 2.5s.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, LiveCaptionAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)
    while (splitter.hasNext()) {
        val cn = ComponentName.unflattenFromString(splitter.next())
        if (cn == expected) return true
    }
    return false
}

@Composable
private fun StylePreview(style: LyricPrefs.Style) {
    val following = style.textColor == LyricPrefs.FOLLOW_CLOCK
    val fill = if (following) Color(0xFFE8E8E8) else Color(style.textColor)
    val wordMode = style.mode == LyricPrefs.MODE_WORD
    val dim = Color(LyricPrefs.dimColorFor(style, fill.toArgb()))

    val base = TextStyle(
        fontSize = 15.sp,
        shadow = if (style.shadowOn) Shadow(
            color = Color(style.shadowColor),
            offset = Offset(style.shadowDxDp, style.shadowDyDp),
            blurRadius = style.shadowRadiusDp,
        ) else null,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF202124), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (style.outlineOn && style.outlineWidthDp > 0f) {
            Text(
                PREVIEW_TEXT,
                color = Color(style.outlineColor),
                style = base.copy(drawStyle = Stroke(width = style.outlineWidthDp * 2f)),
            )
        }
        if (wordMode) {
            Text(
                buildAnnotatedString {
                    val at = (PREVIEW_TEXT.length * PREVIEW_SUNG_FRACTION).toInt()
                    withStyle(SpanStyle(color = fill)) { append(PREVIEW_TEXT.take(at)) }
                    withStyle(SpanStyle(color = dim)) { append(PREVIEW_TEXT.drop(at)) }
                },
                style = base,
            )
        } else {
            Text(PREVIEW_TEXT, color = fill, style = base)
        }
        if (following) {
            Text(
                "  (follows the clock colour)",
                color = Color(0xFF8A8A8A),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 26.dp),
            )
        }
    }
}

@Composable
private fun ModeSetting(style: LyricPrefs.Style, onChange: (LyricPrefs.Style) -> Unit) {
    val wordMode = style.mode == LyricPrefs.MODE_WORD

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleHeader("Per-word (karaoke)", wordMode) {
                onChange(style.copy(mode = if (it) LyricPrefs.MODE_WORD else LyricPrefs.MODE_LINE))
            }
            Text(
                "Each word lights up as it's sung when Lrcmux provides word timings; LRCLIB and line tracks show as plain lines.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (wordMode) {
                Text("Not-yet-sung colour", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = { onChange(style.copy(wordDimColor = LyricPrefs.DERIVE_DIM)) },
                    enabled = style.wordDimColor != LyricPrefs.DERIVE_DIM,
                ) {
                    Text(
                        if (style.wordDimColor == LyricPrefs.DERIVE_DIM) "Fading the lyric colour"
                        else "Fade the lyric colour instead"
                    )
                }

                if (style.wordDimColor == LyricPrefs.DERIVE_DIM) {
                    DpSlider("Dim amount", style.wordDimAlpha, 0.1f, 0.9f, 15) {
                        onChange(style.copy(wordDimAlpha = it))
                    }
                } else {
                    ColorPicker(style.wordDimColor, sentinel = LyricPrefs.DERIVE_DIM) {
                        onChange(style.copy(wordDimColor = it))
                    }
                }

                ToggleHeader("Smooth sweep", style.wordSmooth) {
                    onChange(style.copy(wordSmooth = it))
                }
            }
        }
    }
}

@Composable
private fun SyncSetting(style: LyricPrefs.Style, onChange: (LyricPrefs.Style) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sync offset", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = { onChange(style.copy(syncOffsetMs = LyricPrefs.DEF_SYNC_OFFSET)) },
                    enabled = style.syncOffsetMs != LyricPrefs.DEF_SYNC_OFFSET,
                ) { Text("Reset") }
            }

            Text(
                "${if (style.syncOffsetMs > 0) "+" else ""}${style.syncOffsetMs} ms" +
                    when {
                        style.syncOffsetMs > 0 -> " (lines show earlier)"
                        style.syncOffsetMs < 0 -> " (lines show later)"
                        else -> ""
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = style.syncOffsetMs.toFloat().coerceIn(
                    LyricPrefs.SYNC_OFFSET_MIN.toFloat(),
                    LyricPrefs.SYNC_OFFSET_MAX.toFloat(),
                ),
                onValueChange = { onChange(style.copy(syncOffsetMs = (it / 100f).toInt() * 100)) },
                valueRange = LyricPrefs.SYNC_OFFSET_MIN.toFloat()..LyricPrefs.SYNC_OFFSET_MAX.toFloat(),
            )
        }
    }
}

@Composable
private fun ColorSetting(style: LyricPrefs.Style, onChange: (LyricPrefs.Style) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Lyric colour", style = MaterialTheme.typography.titleSmall)
            TextButton(
                onClick = { onChange(style.copy(textColor = LyricPrefs.FOLLOW_CLOCK)) },
                enabled = style.textColor != LyricPrefs.FOLLOW_CLOCK,
            ) {
                Text(
                    if (style.textColor == LyricPrefs.FOLLOW_CLOCK) "Following the clock colour"
                    else "Follow the clock colour"
                )
            }
            ColorPicker(style.textColor, sentinel = LyricPrefs.FOLLOW_CLOCK) {
                onChange(style.copy(textColor = it))
            }
        }
    }
}

@Composable
private fun ShadowSetting(style: LyricPrefs.Style, onChange: (LyricPrefs.Style) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleHeader("Shadow", style.shadowOn) { onChange(style.copy(shadowOn = it)) }
            if (style.shadowOn) {
                ColorPicker(style.shadowColor, alphaAware = true) {
                    onChange(style.copy(shadowColor = it))
                }
                DpSlider("Blur", style.shadowRadiusDp, 0f, 8f, 16) {
                    onChange(style.copy(shadowRadiusDp = it))
                }
                DpSlider("Offset X", style.shadowDxDp, -4f, 4f, 16) {
                    onChange(style.copy(shadowDxDp = it))
                }
                DpSlider("Offset Y", style.shadowDyDp, -4f, 4f, 16) {
                    onChange(style.copy(shadowDyDp = it))
                }
            }
        }
    }
}

@Composable
private fun OutlineSetting(style: LyricPrefs.Style, onChange: (LyricPrefs.Style) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleHeader("Outline", style.outlineOn) { onChange(style.copy(outlineOn = it)) }
            if (style.outlineOn) {
                ColorPicker(style.outlineColor) { onChange(style.copy(outlineColor = it)) }
                DpSlider("Thickness", style.outlineWidthDp, 0.25f, 3f, 10) {
                    onChange(style.copy(outlineWidthDp = it))
                }
            }
        }
    }
}

@Composable
private fun ToggleHeader(title: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, onCheckedChange = onCheck)
    }
}

@Composable
private fun DpSlider(
    label: String,
    value: Float,
    from: Float,
    to: Float,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        val formatted = if (label.startsWith("Dim")) {
            String.format("%.2f", value)
        } else {
            String.format("%.2f", value).trimEnd('0').trimEnd('.')
        }
        val unit = if (label.startsWith("Dim")) "" else " dp"
        Text("$label: $formatted$unit", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.coerceIn(from, to),
            onValueChange = onChange,
            valueRange = from..to,
            steps = steps,
        )
    }
}

@Composable
private fun Swatches(selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LyricPrefs.PRESETS.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { color -> Swatch(color, color == selected) { onPick(color) } }
            }
        }
    }
}

@Composable
private fun ColorPicker(
    selected: Int,
    alphaAware: Boolean = false,
    sentinel: Int? = null,
    onPick: (Int) -> Unit,
) {
    var hex by remember(selected) {
        mutableStateOf(if (selected == sentinel) "" else LyricPrefs.toHex(selected))
    }
    var invalid by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Swatches(selected) { onPick(it) }
        OutlinedTextField(
            value = hex,
            onValueChange = { input ->
                hex = input
                val parsed = LyricPrefs.parseHex(input)
                invalid = input.isNotBlank() && parsed == null
                if (parsed != null) onPick(parsed)
            },
            label = { Text("Custom colour") },
            placeholder = { Text(if (alphaAware) "#AARRGGBB" else "#RRGGBB") },
            isError = invalid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Swatch(color: Int, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Color(color), CircleShape)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun Step(number: Int, title: String, body: String) {
    Card {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    number.toString(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val AUTHOR_NAME = "Afrizz19"
private const val AUTHOR_URL = "https://github.com/afrizz19"
private const val REPO_URL = "https://github.com/afrizz19/io.github.afrizz19.statusbarlyric"

private val AppColors = darkColorScheme(
    primary = Color(0xFFC4B0FF),
    onPrimary = Color(0xFF2A1366),
    primaryContainer = Color(0xFF42308C),
    onPrimaryContainer = Color(0xFFE7DDFF),
    secondary = Color(0xFF7FD6C8),
    background = Color(0xFF111114),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF111114),
    onSurface = Color(0xFFE6E1E9),
    onSurfaceVariant = Color(0xFFC9C4D0),
    surfaceContainerHighest = Color(0xFF1E1D24),
    outlineVariant = Color(0xFF3A3842),
)

@Composable
private fun Header() {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xFF42308C), Color(0xFF1B5560))),
                RoundedCornerShape(24.dp),
            )
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Status Bar Lyric",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                "Synced lyrics in your status bar. Runs inside SystemUI without touching Dynamic Island.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("v${BuildConfig.VERSION_NAME}")
                Pill("by $AUTHOR_NAME", onClick = { uriHandler.openUri(AUTHOR_URL) })
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.16f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun InactiveWarning() {
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Module not active", style = MaterialTheme.typography.titleSmall)
            Text(
                "LSPosed isn't sharing this app's settings with System UI. Enable this module in LSPosed (scope: System UI), reboot, then reopen the app.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
    )
}

@Composable
private fun Footer() {
    val uriHandler = LocalUriHandler.current
    Spacer(Modifier.height(4.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Made by $AUTHOR_NAME",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { uriHandler.openUri(AUTHOR_URL) }) {
                Text("GitHub profile")
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { uriHandler.openUri(REPO_URL) }) {
                Text("Source code")
            }
        }
        Text(
            "v${BuildConfig.VERSION_NAME} - GPL-3.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
}

private const val PREVIEW_TEXT = "It's a cruel summer"
private const val PREVIEW_SUNG_FRACTION = 0.55f
