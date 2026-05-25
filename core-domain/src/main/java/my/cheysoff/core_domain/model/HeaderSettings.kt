package my.cheysoff.core_domain.model

/**
 * Which sources the notes-list header line may randomly pick from. When all are false the
 * header falls back to a small "Mañana" wordmark.
 */
data class HeaderSettings(
    val showGreetings: Boolean = true,
    val showDailyPhrases: Boolean = true,
    // Stats is a permanent sub-line beneath the motivational line; on by default.
    val showStats: Boolean = true,
)
