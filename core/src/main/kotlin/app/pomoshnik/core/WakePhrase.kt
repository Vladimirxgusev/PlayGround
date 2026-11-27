package app.pomoshnik.core

object WakePhrase {
    private val prefix = Regex("""^слушай\s+меня(?=$|[\s,.:!?—-])[\s,.:!?—-]*""", RegexOption.IGNORE_CASE)

    /** Null means the phrase was absent. Empty means the user paused before the command. */
    fun commandAfter(utterance: String): String? {
        val text = utterance.trim()
        val match = prefix.find(text) ?: return null
        return text.substring(match.range.last + 1).trim()
    }
}
