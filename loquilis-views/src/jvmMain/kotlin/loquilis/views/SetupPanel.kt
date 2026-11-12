package loquilis.views

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
) {
    var runner by mutableStateOf<RunnerState?>(null)
        private set
    var pulling by mutableStateOf<CatalogModel?>(null)
        private set
    var progress by mutableStateOf<PullProgress?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    /** The model suggested for this computer, listed first. */
    val recommended: CatalogModel get() = OllamaSetup.recommend(models, memoryGb)

    private var download: Job? = null

    suspend fun refresh() {
        runner = withContext(Dispatchers.IO) { setup.check() }
    }

    fun start() = setup.start()

    /** Downloads [model] into the runner. Ends quietly when [cancel]led and says why when it fails. */
    suspend fun download(model: CatalogModel) {
        pulling = model
        problem = null
        try {
            runInterruptible(Dispatchers.IO) { setup.pull(model.name) { progress = it } }
            refresh()
        } catch (failure: IOException) {
            problem = failure.message
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
 * get Ollama from its own site, start it, then download a model chosen for this computer.
 */
@Composable
internal fun SetupPanel(setup: SetupState, onClose: () -> Unit, onReady: () -> Unit) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val runner = setup.runner

    // Notices Ollama arriving or starting without asking the person to check.
    LaunchedEffect(runner, setup.pulling) {
        if (runner is RunnerState.Ready) onReady()
        while (setup.pulling == null && (runner == RunnerState.NotInstalled || runner == RunnerState.NotRunning)) {
            delay(POLL_MILLIS)
            setup.refresh()
        }
    }

    Modal(onDismiss = { if (setup.pulling == null) onClose() }, width = 460.dp) {
        Text("Set up a model on this computer", color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        DataGoesLine(DataGoes.StaysHere, "What you write here stays on this computer.")
        when (runner) {
            null -> Note("Checking this computer…")
            RunnerState.NotInstalled -> {
                Note("A model runs inside Ollama, a free app. Get it from Ollama's own site and install it like any other app, then open it once. This card notices when it is ready.")
            }
            RunnerState.NotRunning -> Note("Ollama is installed but not running. Starting it opens the Ollama app, and this card notices when it is ready.")
            RunnerState.NoModels, is RunnerState.Ready -> {
                Note("Ollama is ready. Choose a model to download. It is yours to keep, and later conversations start at once.")
                val recommended = setup.recommended
                (listOf(recommended) + (setup.models - recommended)).forEach { model ->
                    ModelChoice(model, model == recommended, setup) {
                        setup.track(scope.launch { setup.download(model) })
                    }
                }
            }
        }
        setup.problem?.let { Text(it, color = palette.failed, fontSize = 12.sp) }
        Buttons {
            if (setup.pulling != null) {
                ComposerButton("Cancel download", palette.line, palette.ink) { setup.cancel() }
            } else {
                ComposerButton("Close", palette.line, palette.ink, onClick = onClose)
                when (runner) {
                    RunnerState.NotInstalled -> ComposerButton("Get Ollama", palette.accent, palette.userInk) { openInBrowser(OllamaSetup.DOWNLOAD_PAGE) }
                    RunnerState.NotRunning -> ComposerButton("Start Ollama", palette.accent, palette.userInk) { setup.start() }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun ModelChoice(model: CatalogModel, recommended: Boolean, setup: SetupState, onDownload: () -> Unit) {
    val palette = LocalPalette.current
    val pulling = setup.pulling
    val freeGb = setup.freeDiskGb()
    val roomy = freeGb >= model.downloadGb + DISK_SPARE_GB
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (recommended) palette.accent else palette.line, RoundedCornerShape(10.dp))
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
            else -> Buttons { ComposerButton("Download", if (recommended) palette.accent else palette.line, if (recommended) palette.userInk else palette.ink, onClick = onDownload) }
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
