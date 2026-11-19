package app.hushmessenger.xposed.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hushmessenger.xposed.R
import app.hushmessenger.xposed.core.Feature
import app.hushmessenger.xposed.core.Features
import app.hushmessenger.xposed.core.RESCAN_KEY
import app.hushmessenger.xposed.core.SETTINGS_GROUP
import app.hushmessenger.xposed.core.ServiceHolder
import app.hushmessenger.xposed.core.TARGET_PACKAGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val privacyFeatures: List<Feature> = listOf(
    Features.READ_RECEIPTS,
    Features.TYPING,
    Features.SCREENSHOTS,
).mapNotNull(Features::byKey)

private val messageFeatures: List<Feature> = listOf(
    Features.KEEP_UNSENT,
).mapNotNull(Features::byKey)

private val cleanupFeatures: List<Feature> = listOf(
    Features.ADS,
    Features.META_AI,
    Features.STORIES,
).mapNotNull(Features::byKey)

private data class UiMeta(
    val connected: Boolean = false,
    val framework: String? = null,
    val apiVersion: Int? = null,
    val messengerName: String? = null,
    val messengerCode: Long? = null,
)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val service by ServiceHolder.service.collectAsState()

    val messenger = remember(context) { messengerInfo(context) }
    val appVersion = remember(context) { moduleVersion(context) }

    var prefs by remember { mutableStateOf<SharedPreferences?>(null) }
    var values by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var rescanRequested by remember { mutableStateOf(false) }
    var meta by remember {
        mutableStateOf(
            UiMeta(
                messengerName = messenger?.first,
                messengerCode = messenger?.second,
            ),
        )
    }

    LaunchedEffect(service, messenger) {
        val svc = service
        if (svc == null) {
            prefs = null
            values = emptyMap()
            meta = UiMeta(
                messengerName = messenger?.first,
                messengerCode = messenger?.second,
            )
            return@LaunchedEffect
        }

        // Every RemotePreferences read is a binder call; keep it off the main
        // thread so the first frame is not blocked while the framework binds.
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val settings = svc.getRemotePreferences(SETTINGS_GROUP)
                Loaded(
                    prefs = settings,
                    values = Features.all.associate {
                        it.key to settings.getBoolean(it.key, false)
                    },
                    framework = svc.frameworkName,
                    apiVersion = svc.apiVersion,
                )
            }.getOrNull()
        }

        if (loaded == null) {
            prefs = null
            values = emptyMap()
            meta = UiMeta(
                messengerName = messenger?.first,
                messengerCode = messenger?.second,
            )
        } else {
            prefs = loaded.prefs
            values = loaded.values
            meta = UiMeta(
                connected = true,
                framework = loaded.framework,
                apiVersion = loaded.apiVersion,
                messengerName = messenger?.first,
                messengerCode = messenger?.second,
            )
        }
    }

    // RemotePreferences notifies local listeners from apply()/commit() and from
    // framework pushes, so the switches stay in sync with what the hook reads.
    DisposableEffect(prefs) {
        val current = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            values = if (key == null) {
                Features.all.associate { it.key to sp.getBoolean(it.key, false) }
            } else if (Features.byKey(key) != null) {
                values + (key to sp.getBoolean(key, false))
            } else {
                values
            }
        }
        current.registerOnSharedPreferenceChangeListener(listener)
        onDispose { current.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val onToggle: (String, Boolean) -> Unit = { key, value ->
        val current = prefs
        if (current != null) {
            values = values + (key to value)
            current.edit().putBoolean(key, value).apply()
        }
    }

    // The hook runs as Messenger and cannot be handed a cache path, so the
    // module app records a token it bumps on every request. The hook compares
    // it with the token stored beside its cache.
    val onRescan: (() -> Unit)? = if (meta.connected) {
        {
            val current = prefs
            if (current != null) {
                current.edit()
                    .putLong(RESCAN_KEY, System.currentTimeMillis())
                    .apply()
                rescanRequested = true
            }
        }
    } else {
        null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 24.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    Text(
                        text = "HushMessenger",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { StatusCard(meta) }

            item {
                FeatureGroup(
                    title = "Privacy",
                    features = privacyFeatures,
                    values = values,
                    enabled = meta.connected,
                    onToggle = onToggle,
                )
            }

            item {
                FeatureGroup(
                    title = "Messages",
                    features = messageFeatures,
                    values = values,
                    enabled = meta.connected,
                    onToggle = onToggle,
                )
            }

            item {
                FeatureGroup(
                    title = "Cleanup",
                    features = cleanupFeatures,
                    values = values,
                    enabled = meta.connected,
                    onToggle = onToggle,
                )
            }

            item { RestartNote() }
            item {
                Footer(
                    meta = meta,
                    moduleVersion = appVersion,
                    rescanRequested = rescanRequested,
                    onRescan = onRescan,
                )
            }
        }
    }
}

private class Loaded(
    val prefs: SharedPreferences,
    val values: Map<String, Boolean>,
    val framework: String?,
    val apiVersion: Int?,
)

@Composable
private fun StatusCard(meta: UiMeta) {
    val container =
        if (meta.connected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer
    val onContainer =
        if (meta.connected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onErrorContainer

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (meta.connected) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (meta.connected) R.string.status_connected
                        else R.string.status_disconnected,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (meta.connected) statusDetail(meta)
                    else stringResource(R.string.status_enable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
            }
        }
    }
}

private fun statusDetail(meta: UiMeta): String = buildString {
    meta.framework?.takeIf { it.isNotBlank() }?.let { append(it) }
    meta.apiVersion?.let {
        if (isNotEmpty()) append(" · ")
        append("API ").append(it)
    }
    if (isEmpty()) append("Service bound")
}

@Composable
private fun FeatureGroup(
    title: String,
    features: List<Feature>,
    values: Map<String, Boolean>,
    enabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            features.forEachIndexed { index, feature ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
                FeatureRow(
                    feature = feature,
                    checked = values[feature.key] == true,
                    enabled = enabled,
                    onCheckedChange = { onToggle(feature.key, it) },
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(
    feature: Feature,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = feature.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = feature.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun RestartNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.status_restart),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Footer(
    meta: UiMeta,
    moduleVersion: String,
    rescanRequested: Boolean,
    onRescan: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = when {
                meta.messengerName == null -> stringResource(R.string.messenger_missing)
                meta.messengerCode != null ->
                    "Messenger ${meta.messengerName} (${meta.messengerCode})"
                else -> "Messenger ${meta.messengerName}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.resolver_idle),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Module $moduleVersion · Modern Xposed API + DexKit",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRescan != null) {
            TextButton(
                onClick = onRescan,
                enabled = !rescanRequested,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (rescanRequested) {
                        stringResource(R.string.rescan_done)
                    } else {
                        stringResource(R.string.rescan_action)
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun messengerInfo(context: Context): Pair<String?, Long>? = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
    val code =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
    info.versionName to code
}.getOrNull()

private fun moduleVersion(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName ?: "?"
}.getOrDefault("?")
