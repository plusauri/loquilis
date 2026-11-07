package loquilis.app

import java.util.Properties

/**
 * The one place the version is written. Raise [PATCH] and rewrite [LAST_CHANGE] with every change that reaches
 * the window, so a person can tell which code the pixels in front of them came from.
 */
object AppVersion {
    private const val MAJOR = 0
    private const val MINOR = 1
    private const val PATCH = 1
    const val LAST_CHANGE = "Shield icon, version card, delete"

    const val TEXT = "$MAJOR.$MINOR.$PATCH"

    /** What the version card shows. The build fills in the rest, and a copy built without git leaves it out. */
    fun about(): List<Pair<String, String>> {
        val built = Properties()
        ClassLoader.getSystemResourceAsStream("build-info.properties")?.use(built::load)
        return listOf("Version" to TEXT, "Last change" to LAST_CHANGE) +
            listOf("Commit" to "commit", "Committed" to "committed", "Branch" to "branch")
                .mapNotNull { (label, key) -> built.getProperty(key)?.takeIf(String::isNotEmpty)?.let { label to it } }
    }
}
