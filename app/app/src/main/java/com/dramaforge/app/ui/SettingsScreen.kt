package com.dramaforge.app.ui

import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
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
import com.dramaforge.app.generation.GenerationLimits
import com.dramaforge.app.generation.GenerationScheduler
import com.dramaforge.app.generation.GenerationState
import com.dramaforge.app.generation.Prompts
import com.dramaforge.app.generation.ScriptGenerator
import com.dramaforge.app.R
import com.dramaforge.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parametres de generation : cle API, modele, effort, cadence.
 *
 * Les reglages sont lus a l'ouverture et ecrits a chaque changement. L'etat de
 * la generation, lui, vient de WorkManager en flux : une lecture ponctuelle des
 * preferences ne se rafraichissait jamais, ce qui faisait passer un echec pour
 * un bouton sans effet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }

    var keyInput by remember { mutableStateOf("") }
    var keyStored by remember { mutableStateOf(settings.hasApiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<String?>(null) }

    var model by remember { mutableStateOf(settings.model) }
    var effort by remember { mutableStateOf(settings.effort) }
    var episodes by remember { mutableIntStateOf(settings.episodesPerSeries) }
    var review by remember { mutableStateOf(settings.safetyReviewEnabled) }
    var daily by remember { mutableStateOf(settings.dailyEnabled) }

    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val state by GenerationScheduler.state(context)
        .collectAsState(initial = GenerationState())

    // Sans accord de notification (Android 13+), l'avant-plan echoue et chaque
    // execution est coupee a ~10 minutes. On le demande au moment utile.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.settings_personal_mode),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        // --- Cle API ---------------------------------------------------------
        Text(
            stringResource(R.string.settings_section_api),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(
                R.string.settings_cost_warning,
                2 + episodes * (if (review) 2 else 1),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            stringResource(R.string.settings_text_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it; keyError = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_section_api)) },
            placeholder = { Text(stringResource(R.string.settings_api_key_hint)) },
            singleLine = true,
            isError = keyError != null,
            visualTransformation =
                if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = {
                Text(
                    keyError
                        ?: stringResource(
                            if (keyStored) R.string.settings_api_key_stored
                            else R.string.settings_api_key_absent,
                        ),
                )
            },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = keyVisible, onCheckedChange = { keyVisible = it })
            Text(
                stringResource(R.string.settings_api_key_show),
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    settings.saveApiKey(keyInput)
                        .onSuccess {
                            keyStored = settings.hasApiKey
                            keyInput = ""
                            keyError = null
                            testResult = null
                        }
                        .onFailure { keyError = it.message }
                },
                enabled = keyInput.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }

            OutlinedButton(
                onClick = {
                    settings.clearApiKey()
                    // La generation quotidienne sans cle ne ferait qu'echouer
                    // chaque jour : on la coupe en meme temps.
                    settings.dailyEnabled = false
                    daily = false
                    GenerationScheduler.disableDaily(context)
                    keyStored = settings.hasApiKey
                    testResult = null
                },
                enabled = keyStored,
            ) { Text(stringResource(R.string.action_clear)) }
        }

        // Un appel de quelques tokens, pour savoir tout de suite si la cle et le
        // reseau fonctionnent — au lieu de lancer une serie entiere pour
        // decouvrir un 401.
        OutlinedButton(
            onClick = {
                testing = true
                testResult = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        ScriptGenerator(
                            apiKey = settings.apiKey,
                            model = settings.model,
                            effort = settings.effort,
                            prompts = Prompts(context),
                        ).verifyKey()
                    }
                    testing = false
                    testResult = outcome.fold(
                        onSuccess = { okLabel -> okLabel },
                        onFailure = { error -> error.message ?: error.javaClass.simpleName },
                    )
                    testOk = outcome.isSuccess
                }
            },
            enabled = keyStored && !testing,
        ) {
            Text(
                stringResource(
                    if (testing) R.string.settings_test_running else R.string.settings_test_key,
                ),
            )
        }

        testResult?.let { result ->
            Text(
                if (testOk) stringResource(R.string.settings_test_ok, result)
                else stringResource(R.string.settings_error, result),
                style = MaterialTheme.typography.bodySmall,
                color = if (testOk) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()

        // --- Generation ------------------------------------------------------
        Text(
            stringResource(R.string.settings_section_generation),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        LabelledDropdown(
            label = stringResource(R.string.settings_model),
            options = ScriptGenerator.MODELS,
            selected = model,
            onSelect = { model = it; settings.model = it },
        )

        LabelledDropdown(
            label = stringResource(R.string.settings_effort),
            options = ScriptGenerator.EFFORTS,
            selected = effort,
            onSelect = { effort = it; settings.effort = it },
        )

        Text(stringResource(R.string.settings_episodes, episodes))
        Slider(
            value = episodes.toFloat(),
            onValueChange = { episodes = it.toInt().coerceAtLeast(1) },
            onValueChangeFinished = { settings.episodesPerSeries = episodes },
            valueRange = 1f..GenerationLimits.EPISODES_PER_SERIES.toFloat(),
            steps = GenerationLimits.EPISODES_PER_SERIES - 2,
        )

        SwitchRow(
            title = stringResource(R.string.settings_review),
            hint = stringResource(R.string.settings_review_hint),
            checked = review,
            onCheckedChange = { review = it; settings.safetyReviewEnabled = it },
        )

        SwitchRow(
            title = stringResource(R.string.settings_daily),
            hint = stringResource(R.string.settings_daily_hint),
            checked = daily,
            // Sans cle, activer la cadence quotidienne n'a aucun sens.
            enabled = keyStored,
            onCheckedChange = {
                daily = it
                settings.dailyEnabled = it
                if (it) GenerationScheduler.enableDaily(context)
                else GenerationScheduler.disableDaily(context)
            },
        )

        HorizontalDivider()

        if (state.running || state.queued) {
            Text(
                stringResource(if (state.queued) R.string.settings_queued else R.string.settings_running),
            )
            if (state.hasProgress) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedButton(onClick = { GenerationScheduler.cancelNow(context) }) {
                Text(stringResource(R.string.settings_cancel_run))
            }
        } else {
            Button(
                onClick = {
                    askNotificationsIfNeeded()
                    GenerationScheduler.runNow(context)
                },
                enabled = keyStored,
            ) { Text(stringResource(R.string.settings_generate_now)) }
        }

        // L'etat vient de WorkManager, pas d'une lecture ponctuelle des
        // preferences : un echec s'affiche desormais au lieu de disparaitre.
        state.message?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                if (state.failed) stringResource(R.string.settings_error, message)
                else stringResource(R.string.settings_status, message),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Text(
                stringResource(R.string.settings_notifications_needed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelledDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false },
                )
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
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
