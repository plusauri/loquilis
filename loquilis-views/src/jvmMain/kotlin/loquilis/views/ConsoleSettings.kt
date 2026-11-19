package loquilis.views

/**
 * What a person sets for the whole console. The host keeps it between runs; the console only reads and changes it.
 *
 * [hiddenAgents] are left out of the new-conversation choice, though their conversations still open and go on.
 * [keepModelMinutes] is how long a local runner keeps a model in memory after it answers. The two side panels,
 * the conversation list and the inspector, keep whether they show and how wide they are (in dp).
 */
data class ConsoleSettings(
    val offlineOnly: Boolean = false,
    val hiddenAgents: Set<String> = emptySet(),
    val keepModelMinutes: Int = 2,
    val listShown: Boolean = true,
    val listWidth: Int = LIST_WIDTH,
    val inspectorShown: Boolean = true,
    val inspectorWidth: Int = INSPECTOR_WIDTH,
) {
    companion object {
        /** The choices offered for [keepModelMinutes]: freed at once is not one, since every reply would load it again. */
        val KEEP_MINUTES = listOf(1, 2, 5)
        const val LIST_WIDTH = 260
        const val INSPECTOR_WIDTH = 420
    }
}
