package com.music.bitchord.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.history.HistoryCsvMapping
import com.music.bitchord.data.history.HistoryCsvParser
import com.music.bitchord.data.history.HistoryCsvPreview
import com.music.bitchord.data.stats.HistoryImportManager
import com.music.bitchord.data.stats.HistoryImportPreview
import com.music.bitchord.data.stats.HistoryImportProgress
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.ui.icons.SfIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

private enum class ImportDialogStage { LASTFM, CSV_MAPPING, PREVIEW }

@Composable
internal fun HistoryImportSection(lastFmUsername: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { HistoryImportManager(context) }
    val lastFmConfigured = remember { LastFM.isInitialized() }
    var username by remember(lastFmUsername) { mutableStateOf(lastFmUsername) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var dialogStage by remember { mutableStateOf<ImportDialogStage?>(null) }
    var preview by remember { mutableStateOf<HistoryImportPreview?>(null) }
    var csvText by remember { mutableStateOf("") }
    var csvName by remember { mutableStateOf("history.csv") }
    var csvMapping by remember { mutableStateOf<HistoryCsvMapping?>(null) }
    var csvPreview by remember { mutableStateOf<HistoryCsvPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cancelableJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var showUndoConfirm by remember { mutableStateOf(false) }
    var stagedCsv by remember { mutableStateOf<HistoryImportPreview?>(null) }
    var hasImports by remember { mutableStateOf(false) }
    var lastFmResumable by remember { mutableStateOf(false) }

    LaunchedEffect(manager, username) {
        stagedCsv = manager.loadStagedCsv()
        hasImports = withContext(Dispatchers.IO) { ListeningStats.hasImportedHistory() }
        val savedRange = manager.resumableLastFmRange(username)
        lastFmResumable = savedRange != null
        fromText = savedRange?.first.orEmpty()
        toText = savedRange?.second.orEmpty()
    }

    fun selectCsv(uri: Uri) {
        status = "Reading CSV…"
        error = ""
        cancelableJob = scope.launch {
            try {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "history.csv"
                val text = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Could not open the selected CSV file")
                    input.bufferedReader().use { reader ->
                        val buffer = CharArray(8_192)
                        val output = StringBuilder()
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            require(output.length + count <= MAX_CSV_CHARACTERS) { "CSV file is too large (maximum 20 MB of text)" }
                            output.append(buffer, 0, count)
                        }
                        output.toString()
                    }
                }
                val (headers, suggested) = withContext(Dispatchers.Default) { HistoryCsvParser.inspect(text) }
                csvName = name
                csvText = text
                csvMapping = suggested.copy(
                    artistColumn = suggested.artistColumn.takeIf { it >= 0 } ?: 0,
                    trackColumn = suggested.trackColumn.takeIf { it >= 0 } ?: 0,
                    timestampColumn = suggested.timestampColumn.takeIf { it >= 0 } ?: 0,
                    timeZoneId = ZoneId.systemDefault().id,
                )
                csvPreview = HistoryCsvPreview(headers, suggested, emptyList(), emptyList(), 0)
                dialogStage = ImportDialogStage.CSV_MAPPING
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not read the CSV file"
                message = ""
            } finally {
                status = ""
                cancelableJob = null
            }
        }
    }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectCsv(uri)
    }

    SettingsGroup(
        header = "Listening history",
        footer = "Import historical plays into Replay. Existing plays, playlists, favorites and music files are not replaced or changed.",
    ) {
        SettingsRow(
            icon = SfIcons.History,
            title = "Import Last.fm history",
            subtitle = when {
                !lastFmConfigured -> "This build has no Last.fm API configuration; CSV import is still available"
                lastFmResumable -> "Continue a saved import for $username"
                else -> "Fetch public scrobbles for $username, with an optional date range"
            },
            enabled = lastFmConfigured,
            onClick = {
                error = ""
                message = ""
                preview = null
                csvPreview = null
                dialogStage = ImportDialogStage.LASTFM
            },
        )
        RowDivider()
        SettingsRow(
            icon = SfIcons.History,
            title = "Import CSV history",
            subtitle = stagedCsv?.let { "A staged import is ready to review (${it.matched} records)" }
                ?: "Choose a CSV export, map its columns, and review it before saving",
            onClick = {
                if (stagedCsv != null) {
                    preview = stagedCsv
                    dialogStage = ImportDialogStage.PREVIEW
                } else {
                    csvPicker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                }
            },
        )
        if (hasImports) {
            RowDivider()
            SettingsRow(
                icon = SfIcons.History,
                title = "Undo latest history import",
                subtitle = "Remove only the most recently imported batch",
                onClick = { showUndoConfirm = true },
            )
        }
        if (message.isNotBlank() || error.isNotBlank() || status.isNotBlank()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (dialogStage != null) {
        HistoryImportDialog(
            stage = dialogStage!!,
            username = username,
            onUsername = { username = it },
            fromText = fromText,
            onFromText = { fromText = it },
            toText = toText,
            onToText = { toText = it },
            preview = preview,
            csvName = csvName,
            csvPreview = csvPreview,
            csvText = csvText,
            csvMapping = csvMapping,
            onCsvMapping = { csvMapping = it },
            busy = busy,
            status = status,
            error = error,
            onCancel = {
                cancelableJob?.cancel()
                if (!busy) dialogStage = null
            },
            onFetchLastFm = {
                error = ""
                message = ""
                busy = true
                status = "Connecting to Last.fm…"
                cancelableJob = scope.launch {
                    try {
                        val start = fromText.trim().takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
                        val end = toText.trim().takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
                        val result = manager.previewLastFm(username, start, end) { progress ->
                            status = "Fetched ${progress.fetched} rows · page ${progress.pagesComplete} of ${progress.pagesTotal}"
                        }
                        preview = result
                        lastFmResumable = true
                        dialogStage = ImportDialogStage.PREVIEW
                        status = ""
                    } catch (cancelled: CancellationException) {
                        status = "Import paused safely. Reopen this option to resume from the last saved page."
                        lastFmResumable = manager.hasResumableLastFm(username)
                    } catch (failure: Exception) {
                        error = failure.message ?: "Could not fetch Last.fm history"
                        status = ""
                    } finally {
                        busy = false
                        cancelableJob = null
                    }
                }
            },
            onPreviewCsv = {
                val mapping = csvMapping ?: return@HistoryImportDialog
                busy = true
                error = ""
                status = "Validating CSV rows…"
                cancelableJob = scope.launch {
                    try {
                        val parsed = withContext(Dispatchers.Default) { HistoryCsvParser.parse(csvText, mapping) }
                        csvPreview = parsed
                        val result = manager.previewCsv(
                            rows = parsed.records,
                            sourceName = csvName,
                            timeZoneId = mapping.timeZoneId,
                            duplicateRows = parsed.duplicateRows,
                            invalidRows = parsed.invalidRows.size,
                        )
                        preview = result
                        stagedCsv = result
                        dialogStage = ImportDialogStage.PREVIEW
                    } catch (cancelled: CancellationException) {
                        error = "CSV validation cancelled"
                    } catch (failure: Exception) {
                        error = failure.message ?: "Could not validate the CSV file"
                    } finally {
                        busy = false
                        status = ""
                        cancelableJob = null
                    }
                }
            },
            onCommit = {
                val current = preview ?: return@HistoryImportDialog
                busy = true
                error = ""
                status = "Saving imported history…"
                cancelableJob = scope.launch {
                    try {
                        val result = manager.commit(current) { progress ->
                            status = "Saved ${progress.fetched} records · month ${progress.pagesComplete} of ${progress.pagesTotal}"
                        }
                        message = "Imported ${result.imported} listens; skipped ${result.duplicate} duplicates. Replay is updated."
                        stagedCsv = null
                        hasImports = withContext(Dispatchers.IO) { ListeningStats.hasImportedHistory() }
                        lastFmResumable = manager.hasResumableLastFm(username)
                        dialogStage = null
                    } catch (cancelled: CancellationException) {
                        error = "Import paused safely. The staged records remain available; resume and confirm again to finish."
                        lastFmResumable = manager.hasResumableLastFm(username)
                    } catch (failure: Exception) {
                        error = failure.message ?: "Could not save this import. The staged preview is retained for retry."
                    } finally {
                        busy = false
                        status = ""
                        cancelableJob = null
                    }
                }
            },
            onBackToMapping = { dialogStage = ImportDialogStage.CSV_MAPPING },
        )
    }

    if (showUndoConfirm) {
        AlertDialog(
            onDismissRequest = { showUndoConfirm = false },
            title = { Text("Undo latest history import?") },
            text = { Text("Only the most recently imported batch will be removed. Other imported batches, native listening statistics, playlists, favorites and files are left untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    showUndoConfirm = false
                    scope.launch {
                        try {
                            val removed = manager.undoLatestImport()
                            message = "Undid $removed imported listens."
                            hasImports = withContext(Dispatchers.IO) { ListeningStats.hasImportedHistory() }
                        } catch (failure: Exception) {
                            error = failure.message ?: "Could not undo the import"
                        }
                    }
                }) { Text("Undo import") }
            },
            dismissButton = { TextButton(onClick = { showUndoConfirm = false }) { Text("Keep history") } },
        )
    }
}

@Composable
private fun HistoryImportDialog(
    stage: ImportDialogStage,
    username: String,
    onUsername: (String) -> Unit,
    fromText: String,
    onFromText: (String) -> Unit,
    toText: String,
    onToText: (String) -> Unit,
    preview: HistoryImportPreview?,
    csvName: String,
    csvPreview: HistoryCsvPreview?,
    csvText: String,
    csvMapping: HistoryCsvMapping?,
    onCsvMapping: (HistoryCsvMapping) -> Unit,
    busy: Boolean,
    status: String,
    error: String,
    onCancel: () -> Unit,
    onFetchLastFm: () -> Unit,
    onPreviewCsv: () -> Unit,
    onCommit: () -> Unit,
    onBackToMapping: () -> Unit,
) {
    val mapping = csvMapping
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                when (stage) {
                    ImportDialogStage.LASTFM -> "Import Last.fm history"
                    ImportDialogStage.CSV_MAPPING -> "Map CSV columns"
                    ImportDialogStage.PREVIEW -> "Review history import"
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (stage) {
                    ImportDialogStage.LASTFM -> {
                        Text("History is fetched read-only from Last.fm. Nothing is added to Replay until you review and confirm.")
                        OutlinedTextField(
                            value = username,
                            onValueChange = onUsername,
                            label = { Text("Last.fm username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = fromText,
                            onValueChange = onFromText,
                            label = { Text("From date · YYYY-MM-DD · optional") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = toText,
                            onValueChange = onToText,
                            label = { Text("Through date · YYYY-MM-DD · optional") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Leave both dates blank for all public history. A cancelled or interrupted fetch resumes from its last saved page.", style = MaterialTheme.typography.bodySmall)
                    }
                    ImportDialogStage.CSV_MAPPING -> {
                        Text("File: $csvName")
                        val headers = csvPreview?.headers.orEmpty()
                        if (headers.isEmpty()) Text("No CSV header row found", color = MaterialTheme.colorScheme.error)
                        if (mapping != null && headers.isNotEmpty()) {
                            ColumnPicker("Artist column", headers, mapping.artistColumn, false) { onCsvMapping(mapping.copy(artistColumn = it)) }
                            ColumnPicker("Track title column", headers, mapping.trackColumn, false) { onCsvMapping(mapping.copy(trackColumn = it)) }
                            ColumnPicker("Timestamp column", headers, mapping.timestampColumn, false) { onCsvMapping(mapping.copy(timestampColumn = it)) }
                            ColumnPicker("Album column", headers, mapping.albumColumn ?: -1, true) { onCsvMapping(mapping.copy(albumColumn = it.takeIf { it >= 0 })) }
                            ColumnPicker("Listening-time column · not track runtime", headers, mapping.listeningDurationColumn ?: -1, true) { onCsvMapping(mapping.copy(listeningDurationColumn = it.takeIf { it >= 0 })) }
                            ColumnPicker("Duration values are", listOf("Seconds", "Milliseconds", "Minutes"), when (mapping.durationUnitMillis) { 1L -> 1; 60_000L -> 2; else -> 0 }, false) {
                                onCsvMapping(mapping.copy(durationUnitMillis = when (it) { 1 -> 1L; 2 -> 60_000L; else -> 1_000L }))
                            }
                            OutlinedTextField(
                                value = mapping.timeZoneId,
                                onValueChange = { onCsvMapping(mapping.copy(timeZoneId = it)) },
                                label = { Text("Time zone for timestamps without an offset") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = mapping.timestampPattern,
                                onValueChange = { onCsvMapping(mapping.copy(timestampPattern = it)) },
                                label = { Text("Custom date pattern · optional") },
                                placeholder = { Text("dd/MM/yyyy HH:mm") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("ISO-8601 and Unix seconds/milliseconds are recognized automatically. Only map a duration column if it records time actually listened.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    ImportDialogStage.PREVIEW -> {
                        val data = preview
                        if (data == null) {
                            Text("No staged preview is available.")
                        } else {
                            Text("Source: ${if (data.source == HistoryImportManager.LASTFM_SOURCE) "Last.fm · ${data.username}" else data.username}")
                            Text("Rows fetched/read: ${data.fetched}")
                            Text("Valid listens ready: ${data.matched}")
                            Text("Duplicates that will not be added: ${data.duplicate}")
                            Text("Native playback is stored as monthly totals, so exact event overlap cannot be detected; imported listens may overlap native plays and increase Replay counts.", style = MaterialTheme.typography.bodySmall)
                            if (data.skipped > 0) Text("Malformed or unsupported rows skipped: ${data.skipped}")
                            if (data.source == HistoryImportManager.LASTFM_SOURCE) {
                                Text("Pages: ${data.pagesComplete} of ${data.pagesTotal}")
                                Text("Last.fm history supplies plays and timestamps, not actual listening duration; no minutes will be invented.", style = MaterialTheme.typography.bodySmall)
                            }
                            data.events.take(5).forEach { event ->
                                Text("${event.title} · ${event.artist} · ${java.time.Instant.ofEpochMilli(event.timestampMillis).atZone(runCatching { ZoneId.of(event.timeZoneId) }.getOrDefault(ZoneId.of("UTC"))).toLocalDate()}", style = MaterialTheme.typography.bodySmall)
                            }
                            csvPreview?.invalidRows?.take(3)?.forEach { row ->
                                Text("CSV row ${row.row}: ${row.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Confirming appends this batch to local Replay. It does not create playback, downloads, playlists, favorites, or file changes.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            when (stage) {
                ImportDialogStage.LASTFM -> Button(onClick = onFetchLastFm, enabled = !busy && username.isNotBlank()) {
                    Text(if (preview?.source == HistoryImportManager.LASTFM_SOURCE) "Fetch again" else "Fetch history")
                }
                ImportDialogStage.CSV_MAPPING -> Button(onClick = onPreviewCsv, enabled = !busy && mapping != null) { Text("Validate and review") }
                ImportDialogStage.PREVIEW -> Button(onClick = onCommit, enabled = !busy && preview?.matched?.let { it > 0 } == true) { Text("Confirm import") }
            }
        },
        dismissButton = {
            Row {
                if (stage == ImportDialogStage.PREVIEW && preview?.source == HistoryImportManager.CSV_SOURCE && csvMapping != null) {
                    TextButton(onClick = onBackToMapping, enabled = !busy) { Text("Mapping") }
                }
                TextButton(onClick = onCancel) { Text(if (busy) "Cancel" else "Close") }
            }
        },
    )
}

@Composable
private fun ColumnPicker(
    title: String,
    options: List<String>,
    selected: Int,
    allowNone: Boolean,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember(title, options) { mutableStateOf(false) }
    val displayed = when {
        selected < 0 && allowNone -> "None"
        selected in options.indices -> options[selected]
        else -> "Choose column"
    }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.labelSmall)
                Text(displayed, fontWeight = FontWeight.Medium)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) DropdownMenuItem(text = { Text("None") }, onClick = { onSelect(-1); expanded = false })
            options.forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text("${index + 1}. $label") }, onClick = { onSelect(index); expanded = false })
            }
        }
    }
}

private const val MAX_CSV_CHARACTERS = 20 * 1024 * 1024
