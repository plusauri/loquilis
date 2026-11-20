package loquilis.views

import plusauri.ui.ActionKind
import plusauri.ui.DialogConfirmButton
import plusauri.ui.DialogDismissButton
import androidx.compose.ui.input.key.Key
import plusauri.ui.ActionPill
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import loquilis.agents.CatalogModel
import loquilis.agents.OllamaSetup
import loquilis.agents.PullProgress
import loquilis.agents.RunnerState
import loquilis.store.DataGoes

/** Where one agent's setup stands: the runner, a download under way, and what went wrong. */
class SetupState(
    val agent: String,
    private val setup: OllamaSetup,
    val models: List<CatalogModel> = OllamaSetup.catalog,
    val memoryGb: Double = OllamaSetup.installedMemoryGb(),
    val freeDiskGb: () -> Double = OllamaSetup::freeDiskGb,
    /** The model this agent's latest conversation used, offered first. */
    private val lastUsed: String? = null,
    /** Offline only is on, so nothing is downloaded and no site is opened. */
    val offlineOnly: Boolean = false,
    /** The agent calls tools, so only models that can are offered. */
    val toolsOnly: Boolean = false,
) {
    var runner by mutableStateOf<RunnerState?>(null)
        private set
    var pulling by mutableStateOf<CatalogModel?>(null)
        private set
    var progress by mutableStateOf<PullProgress?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    private val usable: List<CatalogModel> get() = models.filter { !toolsOnly || it.tools }

    /** The model suggested for this computer, listed first. */
    val recommended: CatalogModel? get() = usable.takeIf { it.isNotEmpty() }?.let { OllamaSetup.recommend(it, memoryGb) }

    /** Whether [model] leaves most of the memory to everything else. */
    fun suits(model: CatalogModel): Boolean = OllamaSetup.suits(model, memoryGb)

    /** The models on this computer that the agent can use, the one used last first. Empty until the runner is ready. */
    val installed: List<String>
        get() = (runner as? RunnerState.Ready)?.models.orEmpty().filter { !toolsOnly || it in withTools }.sortedByDescending { it == lastUsed }

    /** Catalog models not on this computer yet, the recommended one first. */
    val offered: List<CatalogModel>
        get() {
            val here = (runner as? RunnerState.Ready)?.models.orEmpty().toSet()
            return (listOfNotNull(recommended) + (usable - setOfNotNull(recommended))).filter { it.name !in here }
        }

    private var withTools by mutableStateOf<Set<String>>(emptySet())

    private var download: Job? = null

    suspend fun refresh() {
        val checked = withContext(Dispatchers.IO) { setup.check() }
        if (toolsOnly && checked is RunnerState.Ready) {
            withTools = withContext(Dispatchers.IO) { checked.models.filter(setup::canUseTools).toSet() }
        }
        runner = checked
    }

    fun start() = setup.start()

    /**
     * Downloads [model] into the runner and says whether it arrived. Ends quietly when [cancel]led and says
     * why when it fails.
     */
    suspend fun download(model: CatalogModel): Boolean {
        pulling = model
        problem = null
        return try {
            runInterruptible(Dispatchers.IO) { setup.pull(model.name) { progress = it } }
            refresh()
            true
        } catch (failure: IOException) {
            problem = failure.message
            false
        } finally {
            pulling = null
            progress = null
        }
    }

    internal fun track(job: Job) {
        download = job
    }

    fun cancel() {
        download?.cancel()
    }
}

/**
 * Takes a newcomer from no runner to a model that answers, a step at a time and nothing without a click:
 * get Ollama from its own site, start it, download a model chosen for this computer. Once one is here, it
 * is where each conversation's model is chosen. Return starts with the first, Escape closes.
 */
@Composable
internal fun SetupPanel(setup: SetupState, onClose: () -> Unit, onBack: () -> Unit, onStart: (model: String) -> Unit) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val runner = setup.runner

    // Notices Ollama arriving or starting without asking the person to check.
    LaunchedEffect(runner, setup.pulling) {
        while (setup.pulling == null && (runner == RunnerState.NotInstalled || runner == RunnerState.NotRunning)) {
            delay(POLL_MILLIS)
            setup.refresh()
        }
    }

    val ready = runner is RunnerState.Ready && setup.installed.isNotEmpty()
    ConsoleDialog(
        if (ready) "Choose a model" else "Set up a model on this computer",
        // A download keeps the dialog: Cancel download is the way out of it.
        onDismiss = { if (setup.pulling == null) onClose() },
        width = 480.dp,
        keys = { key ->
            val first = setup.installed.firstOrNull()
            (key == Key.Enter && first != null && setup.pulling == null).also { if (it) onStart(first!!) }
        },
        footer = {
            if (setup.pulling != null) {
                DialogDismissButton("Cancel download", onClick = { setup.cancel() })
            } else {
                // Back to the new-conversation card, its agent and name as they were; Cancel leaves it all.
                DialogDismissButton("← Back", onClick = onBack)
                DialogDismissButton("Cancel", onClick = onClose)
                when (runner) {
                    RunnerState.NotInstalled -> if (!setup.offlineOnly) DialogConfirmButton("Get Ollama", onClick = { openInBrowser(OllamaSetup.DOWNLOAD_PAGE) })
                    RunnerState.NotRunning -> DialogConfirmButton("Start Ollama", onClick = { setup.start() })
                    else -> {}
                }
            }
        },
    ) {
        if (setup.toolsOnly) DataGoesLine(DataGoes.ModelHere, "The model runs on this computer. Its tools reach the network only when you allow them.")
        else DataGoesLine(DataGoes.StaysHere, "What you write here stays on this computer.")
        when (runner) {
            null -> Note("Checking this computer…")
            RunnerState.NotInstalled ->
                Note(if (setup.offlineOnly) "A model runs inside Ollama, a free app, which is not on this computer yet. Getting it needs the internet: turn off Offline only first." else "A model runs inside Ollama, a free app. Get it from Ollama's own site and install it like any other app, then open it once. This card notices when it is ready.")
            RunnerState.NotRunning -> Note("Ollama is installed but not running. Starting it opens the Ollama app, and this card notices when it is ready.")
            RunnerState.NoModels, is RunnerState.Ready -> {
                if (setup.toolsOnly) Note("This agent works through tools, so only models that can use them are listed.")
                setup.installed.forEachIndexed { index, name ->
                    InstalledChoice(name, setup.models.firstOrNull { it.name == name }, first = index == 0, enabled = setup.pulling == null) { onStart(name) }
                }
                val offered = setup.offered
                if (offered.isNotEmpty() && setup.offlineOnly) {
                    Note("Downloading a model needs the internet. Turn off Offline only to download one.")
                } else if (offered.isNotEmpty()) {
                    Note(
                        if (ready) "Or download another. It is yours to keep."
                        else "Ollama is ready. Choose a model to download. It is yours to keep, and later conversations start at once.",
                    )
                    offered.forEach { model ->
                        ModelChoice(model, model == setup.recommended && setup.suits(model), setup, prominent = !ready) {
                            setup.track(scope.launch { if (setup.download(model)) onStart(model.name) })
                        }
                    }
                }
            }
        }
        if (runner == RunnerState.NotInstalled || runner == RunnerState.NotRunning) {
            Note("Ollama may show its own welcome the first time. You can close it. Signing in is not needed for models on this computer.")
        }
        setup.problem?.let { Text(it, color = palette.failed, fontSize = 12.sp) }
    }
}

/** A model already on this computer, one click from a conversation. */
@Composable
private fun InstalledChoice(name: String, known: CatalogModel?, first: Boolean, enabled: Boolean, onStart: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (first) palette.accent else palette.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(known?.label ?: name, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(name.takeIf { known != null }, known?.let { "about ${gb(it.memoryGb)} of memory while it answers" }).joinToString(" · ").ifEmpty { "On this computer" },
                color = palette.muted,
                fontSize = 11.sp,
            )
        }
        if (enabled) ActionPill("Start", ActionKind.Run, onClick = onStart, selected = first)
    }
}

@Composable
private fun ModelChoice(model: CatalogModel, recommended: Boolean, setup: SetupState, prominent: Boolean, onDownload: () -> Unit) {
    val palette = LocalPalette.current
    val pulling = setup.pulling
    val freeGb = setup.freeDiskGb()
    val roomy = freeGb >= model.downloadGb + DISK_SPARE_GB
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (recommended && prominent) palette.accent else palette.line, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.label, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (recommended) Text("Suits this computer", color = palette.accent, fontSize = 11.sp)
        }
        Text(model.goodFor, color = palette.muted, fontSize = 12.sp)
        Text(
            "${gb(model.downloadGb)} download · about ${gb(model.memoryGb)} of memory while it answers · ${model.maker} · ${model.name}",
            color = palette.muted,
            fontSize = 11.sp,
        )
        when {
            pulling == model -> Progress(setup.progress)
            pulling != null -> {}
            !roomy -> Text("Needs ${gb(model.downloadGb + DISK_SPARE_GB)} of free disk; this computer has ${gb(freeGb)}.", color = palette.failed, fontSize = 11.sp)
            else -> Buttons {
                ActionPill("Download", ActionKind.Add, onClick = onDownload, selected = recommended && prominent)
            }
        }
    }
}

@Composable
private fun Progress(progress: PullProgress?) {
    val palette = LocalPalette.current
    val total = progress?.total ?: 0L
    val share = if (total > 0) (progress!!.completed.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(palette.line)) {
            Box(Modifier.fillMaxWidth(share).height(6.dp).background(palette.accent))
        }
        Text(
            if (total > 0) "${gb(progress!!.completed / GB)} of ${gb(total / GB)}" else progress?.status?.replaceFirstChar(Char::uppercase) ?: "Starting…",
            color = palette.muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = LocalPalette.current.muted, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { content() }
}

private fun gb(value: Double) = if (value < 1) "%.0f MB".format(value * 1000) else "%.1f GB".format(value)

private fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

private const val POLL_MILLIS = 2_000L
private const val DISK_SPARE_GB = 2.0
private const val GB = 1_000_000_000.0
