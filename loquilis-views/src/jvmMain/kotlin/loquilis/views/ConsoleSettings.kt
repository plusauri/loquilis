package loquilis.views

/**
 * What a person sets for the whole console. The host keeps it between runs; the console only reads and changes it.
 *
 * [hiddenAgents] are left out of the new-conversation links, though their conversations still open and go on.
 * [keepModelMinutes] is how long a local runner keeps a model in memory after it answers.
 */
data class ConsoleSettings(
    val offlineOnly: Boolean = false,
    val hiddenAgents: Set<String> = emptySet(),
    val keepModelMinutes: Int = 2,
) {
    companion object {
        /** The choices offered for [keepModelMinutes]: freed at once is not one, since every reply would load it again. */
        val KEEP_MINUTES = listOf(1, 2, 5)
    }
}
