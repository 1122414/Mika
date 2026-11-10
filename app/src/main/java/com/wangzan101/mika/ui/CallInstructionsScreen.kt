package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallRuntime
import com.wangzan101.mika.calls.codex.*
import com.wangzan101.mika.calls.instructions.CallInstruction
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun CallInstructionsScreen(runtime: CallRuntime, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = runtime.instructions
    val rules by store.items.collectAsState()
    val storageError by store.error.collectAsState()
    val providers = remember(context) { ProviderStore.get(context) }
    val owner = remember(context, providers) { CodexOwnerRuntime(context, providers, ownerMemory = store::promptSnapshot) }
    val engine by owner.state.collectAsState()
    val messages by owner.messages.collectAsState()
    val configuredProviders by providers.providers.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf<CallInstruction?>(null) }
    var deleting by remember { mutableStateOf<CallInstruction?>(null) }
    var showNewChat by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var showConnection by rememberSaveable { mutableStateOf(false) }
    var savingConnection by remember { mutableStateOf(false) }
    val busy = savingConnection || engine.phase in setOf(CodexOwnerPhase.STARTING, CodexOwnerPhase.RESTORING,
        CodexOwnerPhase.RESPONDING, CodexOwnerPhase.CANCELLING)
    val savedLabel = stringResource(R.string.call_instructions_saved)
    val failedLabel = stringResource(R.string.call_instructions_failed)
    val deliveryTitle = stringResource(R.string.call_instructions_delivery)
    val deliveryWhen = stringResource(R.string.call_instructions_delivery_when)
    val deliveryReply = stringResource(R.string.call_instructions_delivery_reply)
    val businessTitle = stringResource(R.string.call_instructions_business)
    val businessWhen = stringResource(R.string.call_instructions_business_when)
    val businessReply = stringResource(R.string.call_instructions_business_reply)

    fun mutate(action: () -> Unit) {
        scope.launch {
            feedback = withContext(Dispatchers.IO) {
                runCatching { action(); savedLabel }.getOrElse { failedLabel }
            }
        }
    }
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    LaunchedEffect(owner) { owner.resume() }
    LaunchedEffect(engine.model) { if (model.isBlank()) model = engine.model.orEmpty() }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    BackHandler { if (draft != null) draft = null else onBack() }

    PageScaffold(stringResource(R.string.call_instructions_title), onBack,
        Modifier.testTag("call-instructions").imePadding()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text(stringResource(R.string.call_instructions_rules)) })
            FilterChip(tab == 1, { tab = 1 }, label = { Text(stringResource(R.string.call_instructions_chat)) })
        }
        if (tab == 0) {
            FooterNote(stringResource(R.string.call_instructions_intro))
            MikaButton(stringResource(R.string.call_instructions_add), {
                draft = CallInstruction("", "", "", "")
            }, Modifier.fillMaxWidth().testTag("call-instruction-add"))
            if (rules.isEmpty()) {
                FooterNote(stringResource(R.string.call_instructions_empty))
                GroupLabel(stringResource(R.string.call_instructions_presets))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { draft = CallInstruction("", deliveryTitle, deliveryWhen, deliveryReply) }) { Text(deliveryTitle) }
                    TextButton(onClick = { draft = CallInstruction("", businessTitle, businessWhen, businessReply) }) { Text(businessTitle) }
                }
            }
            rules.forEach { rule ->
                MikaCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(rule.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(rule.enabled, { value -> mutate { store.setEnabled(rule.id, value) } })
                    }
                    if (rule.expiresAtMs?.let { it <= System.currentTimeMillis() } == true) {
                        Text(stringResource(R.string.call_instructions_expired), color = MaterialTheme.colorScheme.error)
                    }
                    Text(rule.situation, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(rule.response) }
                    Row {
                        TextButton(onClick = { draft = rule }) { Text(stringResource(R.string.call_instructions_edit)) }
                        TextButton(onClick = { deleting = rule }) { Text(stringResource(R.string.call_instructions_delete)) }
                    }
                }
            }
        } else {
            FooterNote(stringResource(R.string.call_instructions_codex_intro))
            if (engine.phase == CodexOwnerPhase.UNAVAILABLE) {
                Text(stringResource(R.string.call_instructions_unavailable), color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { showConnection = !showConnection }) { Text(stringResource(R.string.call_instructions_provider)) }
            if (showConnection || engine.phase == CodexOwnerPhase.NEEDS_PROVIDER) {
                MikaCard(Modifier.fillMaxWidth()) {
                    val existing = configuredProviders.filter {
                        CodexOwnerRuntime.isOfficialProvider(it)
                    }
                    OutlinedTextField(model, { model = it.take(160) }, label = { Text(stringResource(R.string.call_instructions_model)) },
                        supportingText = { Text(stringResource(R.string.call_instructions_model_hint)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().keepsKeyboard(), enabled = !busy)
                    existing.forEach { provider ->
                        TextButton(onClick = { scope.launch { owner.configureProvider(provider.id, model) } },
                            enabled = !busy && model.isNotBlank()) {
                            Text(stringResource(R.string.call_instructions_use_existing) + " · " + provider.name)
                        }
                    }
                    OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.call_instructions_api_key)) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        modifier = Modifier.fillMaxWidth().keepsKeyboard(), enabled = !busy)
                    MikaButton(stringResource(R.string.call_instructions_connect), {
                        val entered = key.trim()
                        val requestedModel = model.trim()
                        if (engine.threadId == null && CodexOwnerRuntime.modelName.matches(requestedModel) &&
                            entered.length in 1..2_048 && entered.all { it.code in 33..126 }) {
                            savingConnection = true
                            scope.launch {
                                try {
                                    val provider = withContext(Dispatchers.IO) {
                                        // Dedicated identity: never replace another Mika connection or credential.
                                        runCatching { providers.connect("Codex · " + java.util.UUID.randomUUID().toString(),
                                            ProviderConfig.Kind.OpenAiResponses, "https://api.openai.com/v1",
                                            entered, emptyList(), untested = true) }.getOrNull()
                                    }
                                    key = ""
                                    if (provider == null || !owner.configureProvider(provider.id, requestedModel)) feedback = failedLabel
                                    else showConnection = false
                                } finally { savingConnection = false }
                            }
                        } else feedback = failedLabel
                    }, enabled = !busy && key.isNotBlank() && CodexOwnerRuntime.modelName.matches(model.trim()) && engine.threadId == null)
                    if (engine.threadId != null) FooterNote(stringResource(R.string.call_instructions_new_chat_confirm))
                    FooterNote(stringResource(R.string.call_instructions_api_note))
                }
            }
            engine.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { owner.resume() } }, enabled = !busy) { Text(stringResource(R.string.call_instructions_continue)) }
                TextButton(onClick = { showNewChat = true }, enabled = !busy) { Text(stringResource(R.string.call_instructions_new_chat)) }
            }
            messages.forEach { message ->
                MikaCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(if (message.role == CodexOwnerRole.USER) R.string.call_instructions_owner
                        else R.string.call_instructions_assistant), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(message.text) }
                    if (message.role == CodexOwnerRole.ASSISTANT && message.complete && message.text.isNotBlank()) {
                        TextButton(onClick = {
                            draft = CallInstruction("", "", "", message.text)
                        }) { Text(stringResource(R.string.call_instructions_save_reply)) }
                    }
                }
            }
            if (busy) {
                Text(stringResource(R.string.call_instructions_busy))
                TextButton(onClick = { scope.launch { owner.cancel() } }) { Text(stringResource(R.string.call_instructions_stop)) }
            } else {
                OutlinedTextField(input, { input = it.take(CodexOwnerRuntime.MAX_INPUT_CHARS) }, label = { Text(stringResource(R.string.call_instructions_message)) },
                    modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("call-owner-input"), minLines = 2, maxLines = 8)
                MikaButton(stringResource(R.string.call_instructions_send), {
                    val text = input
                    scope.launch { if (owner.send(text)) input = "" }
                }, enabled = input.isNotBlank() && engine.providerId != null && engine.phase != CodexOwnerPhase.UNAVAILABLE,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        (storageError ?: feedback)?.let { FooterNote(it) }
    }
    draft?.let { row ->
        InstructionEditor(row, feedback.takeIf { it == failedLabel }, onDismiss = { draft = null }, onSave = { edited ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { store.save(edited) }.isSuccess }
                if (ok) { draft = null; tab = 0; feedback = savedLabel } else feedback = failedLabel
            }
        })
    }
    if (deleting != null) AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(stringResource(R.string.call_instructions_delete)) },
        text = { Text(stringResource(R.string.call_instructions_delete_confirm)) },
        confirmButton = { TextButton(onClick = { val id = deleting!!.id; deleting = null; mutate { store.remove(id) } }) { Text(stringResource(R.string.call_instructions_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.call_instructions_cancel)) } })
    if (showNewChat) AlertDialog(onDismissRequest = { showNewChat = false },
        title = { Text(stringResource(R.string.call_instructions_new_chat)) },
        text = { Text(stringResource(R.string.call_instructions_new_chat_confirm)) },
        confirmButton = { TextButton(onClick = { showNewChat = false; scope.launch { owner.newChat() } }) { Text(stringResource(R.string.call_instructions_new_chat)) } },
        dismissButton = { TextButton(onClick = { showNewChat = false }) { Text(stringResource(R.string.call_instructions_cancel)) } })
}

@Composable
private fun InstructionEditor(row: CallInstruction, saveError: String?, onDismiss: () -> Unit, onSave: (CallInstruction) -> Unit) {
    var title by remember(row) { mutableStateOf(row.title) }
    var situation by remember(row) { mutableStateOf(row.situation) }
    var response by remember(row) { mutableStateOf(row.response) }
    var expiry by remember(row) { mutableStateOf(row.expiresAtMs) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (row.id.isBlank()) R.string.call_instructions_add else R.string.call_instructions_edit)) },
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        text = {
            val titleFocus = remember { FocusRequester() }
            val window = LocalWindowInfo.current
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(row, window) {
                // A text field can take focus before the dialog owns the IME window.
                snapshotFlow { window.isWindowFocused }.first { it }
                titleFocus.requestFocus()
                withFrameNanos { }
                keyboard?.show()
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(64) }, label = { Text(stringResource(R.string.call_instructions_heading)) },
                    modifier = Modifier.focusRequester(titleFocus).testTag("call-instruction-title-input"), singleLine = true)
                OutlinedTextField(situation, { situation = it.take(200) }, label = { Text(stringResource(R.string.call_instructions_situation)) }, maxLines = 3)
                OutlinedTextField(response, { response = it }, label = { Text(stringResource(R.string.call_instructions_response)) },
                    supportingText = { Text(response.length.toString() + " / 800") }, isError = response.length > 800, minLines = 3, maxLines = 7)
                Text(stringResource(R.string.call_instructions_expiry), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(expiry == null, { expiry = null }, label = { Text(stringResource(R.string.call_instructions_forever)) })
                    FilterChip(expiry == LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), { expiry = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() },
                        label = { Text(stringResource(R.string.call_instructions_today)) })
                }
                TextButton(onClick = { expiry = System.currentTimeMillis() + 2 * 60 * 60 * 1_000L }) {
                    Text(stringResource(R.string.call_instructions_two_hours))
                }
                expiry?.let { Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)), style = MaterialTheme.typography.bodySmall) }
                if (response.length > 800) Text(stringResource(R.string.call_instructions_review_limit))
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(row.copy(title = title, situation = situation, response = response, expiresAtMs = expiry)) },
            enabled = title.isNotBlank() && situation.isNotBlank() && response.isNotBlank() && response.length <= 800) {
            Text(stringResource(R.string.call_instructions_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.call_instructions_cancel)) } })
}
