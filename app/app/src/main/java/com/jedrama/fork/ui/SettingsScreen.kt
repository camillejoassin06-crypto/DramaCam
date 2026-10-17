package com.jedrama.fork.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jedrama.fork.R
import com.jedrama.fork.generation.ClaudeTextModel
import com.jedrama.fork.generation.GenerationLimits
import com.jedrama.fork.generation.GenerationScheduler
import com.jedrama.fork.generation.GenerationState
import com.jedrama.fork.generation.TextProvider
import com.jedrama.fork.library.Library
import com.jedrama.fork.media.Ffmpeg
import com.jedrama.fork.media.ImageProvider
import com.jedrama.fork.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parametres : modele de texte et cle, images, cadence quotidienne, rendu.
 * L'etat de la generation vient de WorkManager en flux : un echec s'affiche
 * au lieu de passer pour un bouton sans effet.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(settings.provider) }
    var keyInput by remember { mutableStateOf("") }
    var keyStored by remember { mutableStateOf(settings.hasApiKey()) }
    var keyVisible by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var claudeModel by remember { mutableStateOf(settings.claudeModel) }
    var effort by remember { mutableStateOf(settings.effort) }
    var geminiModel by remember { mutableStateOf(settings.geminiModel) }

    var images by remember { mutableStateOf(settings.imageProvider) }
    var episodes by remember { mutableIntStateOf(settings.episodesPerSeries) }
    var perDay by remember { mutableIntStateOf(settings.episodesPerDay) }
    var review by remember { mutableStateOf(settings.safetyReviewEnabled) }
    var daily by remember { mutableStateOf(settings.dailyEnabled) }
    var charging by remember { mutableStateOf(settings.onlyWhileCharging) }
    var wifi by remember { mutableStateOf(settings.onlyOnUnmetered) }
    var hd by remember { mutableStateOf(settings.hdRendering) }
    var ambience by remember { mutableStateOf(settings.ambience) }

    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var ffmpegInfo by remember { mutableStateOf<String?>(null) }

    val stateFlow = remember { GenerationScheduler.state(context) }
    val state by stateFlow.collectAsState(initial = GenerationState())

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun reschedule() {
        if (settings.dailyEnabled) GenerationScheduler.enableDaily(context)
    }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.settings_personal_mode),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )

        // --- Generation : etat et declenchement ----------------------------
        HorizontalDivider()
        Section(stringResource(R.string.settings_section_generation))

        if (state.running || state.queued) {
            Text(stringResource(if (state.queued) R.string.settings_queued else R.string.settings_running))
            if (state.hasProgress) {
                LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = { GenerationScheduler.cancelNow(context) }) { Text(stringResource(R.string.settings_cancel_run)) }
        } else {
            Button(
                onClick = { askNotificationsIfNeeded(); GenerationScheduler.runNow(context) },
                enabled = keyStored,
            ) { Text(stringResource(R.string.settings_generate_now, perDay)) }
        }
        // Le job quotidien n'expose pas son echec dans WorkManager : a defaut de
        // message en direct, on affiche le dernier etat ecrit par le worker.
        val statusMessage = when {
            state.failed -> state.message?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.generation_status_unknown_failure)
            else -> state.message ?: settings.lastStatus.takeUnless { state.running || state.queued }
        }
        statusMessage
            ?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                if (state.failed) stringResource(R.string.settings_error, message) else stringResource(R.string.settings_status, message),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SwitchRow(
            title = stringResource(R.string.settings_daily),
            hint = stringResource(R.string.settings_daily_hint),
            checked = daily,
            enabled = keyStored,
            onCheckedChange = {
                daily = it
                settings.dailyEnabled = it
                if (it) { askNotificationsIfNeeded(); GenerationScheduler.enableDaily(context) }
                else GenerationScheduler.disableDaily(context)
            },
        )
        SwitchRow(stringResource(R.string.settings_charging), stringResource(R.string.settings_charging_hint), charging,
            { charging = it; settings.onlyWhileCharging = it; reschedule() })
        SwitchRow(stringResource(R.string.settings_wifi), stringResource(R.string.settings_wifi_hint), wifi,
            { wifi = it; settings.onlyOnUnmetered = it; reschedule() })

        Text(stringResource(R.string.settings_per_day, perDay))
        Slider(
            value = perDay.toFloat(),
            onValueChange = { perDay = it.toInt().coerceAtLeast(1) },
            onValueChangeFinished = { settings.episodesPerDay = perDay },
            valueRange = 1f..SettingsStore.MAX_EPISODES_PER_DAY.toFloat(),
            steps = SettingsStore.MAX_EPISODES_PER_DAY - 2,
        )
        Text(stringResource(R.string.settings_episodes, episodes))
        Slider(
            value = episodes.toFloat(),
            onValueChange = { episodes = it.toInt().coerceAtLeast(1) },
            onValueChangeFinished = { settings.episodesPerSeries = episodes },
            valueRange = 1f..GenerationLimits.EPISODES_PER_SERIES.toFloat(),
            steps = GenerationLimits.EPISODES_PER_SERIES - 2,
        )
        Text(
            stringResource(R.string.settings_episodes_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // --- Modele de texte ------------------------------------------------
        HorizontalDivider()
        Section(stringResource(R.string.settings_section_text))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextProvider.entries.forEach { option ->
                FilterChip(
                    selected = provider == option,
                    onClick = {
                        provider = option
                        settings.provider = option
                        keyStored = settings.hasApiKey()
                        keyInput = ""
                        testResult = null
                    },
                    label = {
                        Text(stringResource(if (option == TextProvider.CLAUDE) R.string.provider_claude else R.string.provider_gemini))
                    },
                )
            }
        }
        Text(
            stringResource(if (provider == TextProvider.CLAUDE) R.string.settings_claude_cost else R.string.settings_gemini_cost),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it; keyError = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(if (provider == TextProvider.CLAUDE) R.string.settings_key_claude else R.string.settings_key_gemini)) },
            placeholder = { Text(if (provider == TextProvider.CLAUDE) "sk-ant-â€¦" else "AIzaâ€¦") },
            singleLine = true,
            isError = keyError != null,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = {
                Text(keyError ?: stringResource(if (keyStored) R.string.settings_api_key_stored else R.string.settings_api_key_absent))
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = keyVisible, onCheckedChange = { keyVisible = it })
            Text(stringResource(R.string.settings_api_key_show), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    settings.saveApiKey(keyInput)
                        .onSuccess { keyStored = settings.hasApiKey(); keyInput = ""; testResult = null }
                        .onFailure { keyError = it.message }
                },
                enabled = keyInput.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
            OutlinedButton(
                onClick = {
                    settings.clearApiKey()
                    keyStored = settings.hasApiKey()
                    if (!keyStored) {
                        settings.dailyEnabled = false
                        daily = false
                        GenerationScheduler.disableDaily(context)
                    }
                    testResult = null
                },
                enabled = keyStored,
            ) { Text(stringResource(R.string.action_clear)) }
        }

        if (provider == TextProvider.CLAUDE) {
            LabelledDropdown(stringResource(R.string.settings_model), ClaudeTextModel.MODELS, claudeModel) {
                claudeModel = it; settings.claudeModel = it
            }
            LabelledDropdown(stringResource(R.string.settings_effort), ClaudeTextModel.EFFORTS, effort) {
                effort = it; settings.effort = it
            }
        } else {
            OutlinedTextField(
                value = geminiModel,
                onValueChange = { geminiModel = it; settings.geminiModel = it },
                label = { Text(stringResource(R.string.settings_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedButton(
            onClick = {
                testing = true
                testResult = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching { settings.textModel().let { "${it.label} : ${it.verify()}" } }
                    }
                    testing = false
                    testOk = outcome.isSuccess
                    testResult = outcome.fold({ it }, { it.message ?: it.javaClass.simpleName })
                }
            },
            enabled = keyStored && !testing,
        ) { Text(stringResource(if (testing) R.string.settings_test_running else R.string.settings_test_key)) }
        testResult?.let { result ->
            Text(
                if (testOk) stringResource(R.string.settings_test_ok, result) else stringResource(R.string.settings_error, result),
                style = MaterialTheme.typography.bodySmall,
                color = if (testOk) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }
        SwitchRow(stringResource(R.string.settings_review), stringResource(R.string.settings_review_hint), review,
            { review = it; settings.safetyReviewEnabled = it })

        // --- Images et rendu ------------------------------------------------
        HorizontalDivider()
        Section(stringResource(R.string.settings_section_render))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageProvider.entries.forEach { option ->
                FilterChip(
                    selected = images == option,
                    onClick = { images = option; settings.imageProvider = option },
                    label = {
                        Text(stringResource(if (option == ImageProvider.POLLINATIONS) R.string.images_ai else R.string.images_offline))
                    },
                )
            }
        }
        Text(
            stringResource(if (images == ImageProvider.POLLINATIONS) R.string.images_ai_hint else R.string.images_offline_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(stringResource(R.string.settings_hd), stringResource(R.string.settings_hd_hint), hd,
            { hd = it; settings.hdRendering = it })
        SwitchRow(stringResource(R.string.settings_ambience), stringResource(R.string.settings_ambience_hint), ambience,
            { ambience = it; settings.ambience = it })

        OutlinedButton(onClick = {
            scope.launch {
                ffmpegInfo = runCatching { Ffmpeg(context).version() }.getOrElse { it.message }
            }
        }) { Text(stringResource(R.string.settings_ffmpeg_check)) }
        ffmpegInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            stringResource(R.string.settings_storage, Formatter.formatShortFileSize(context, remember { Library(context).bytesUsed() })),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        var unrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
        if (!unrestricted) {
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }
                unrestricted = isIgnoringBatteryOptimizations(context)
            }) { Text(stringResource(R.string.settings_battery)) }
            Text(
                stringResource(R.string.settings_battery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Text(
                stringResource(R.string.settings_notifications_needed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelledDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

