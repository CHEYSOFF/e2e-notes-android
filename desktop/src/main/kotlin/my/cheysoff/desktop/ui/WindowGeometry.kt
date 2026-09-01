package my.cheysoff.desktop.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.prefs.Preferences

/**
 * Where the window was and how wide the sidebar was, remembered between launches.
 *
 * Stored in [Preferences] rather than in a file of our own: it is a handful of integers with no
 * privacy weight (a window rectangle, not note content), and the platform already owns a per-user
 * place to put exactly that. Anything that IS note content belongs in the encrypted store instead.
 *
 * Every read has a default and every write is best-effort. A locked-down machine where the
 * preferences backing store is unwritable should open a normal window, not fail to start.
 */
object WindowGeometry {

    private const val KEY_WIDTH = "window.width"
    private const val KEY_HEIGHT = "window.height"
    private const val KEY_X = "window.x"
    private const val KEY_Y = "window.y"
    private const val KEY_MAXIMIZED = "window.maximized"
    private const val KEY_SIDEBAR = "sidebar.width"

    /** Undersized on purpose only in the sense that it must fit a 1366x768 laptop screen. */
    val DefaultWidth = 1280.dp
    val DefaultHeight = 800.dp
    val DefaultSidebarWidth = 300.dp

    private val prefs: Preferences? = runCatching {
        Preferences.userRoot().node("my/cheysoff/manana/desktop")
    }.getOrNull()

    fun width(): Dp = readDp(KEY_WIDTH, DefaultWidth)
    fun height(): Dp = readDp(KEY_HEIGHT, DefaultHeight)
    fun sidebarWidth(): Dp = readDp(KEY_SIDEBAR, DefaultSidebarWidth)
    fun isMaximized(): Boolean = prefs?.getBoolean(KEY_MAXIMIZED, false) ?: false

    /**
     * The saved top-left, or null when nothing has been saved. Null means "let the platform
     * centre the window" — restoring a position onto a monitor that has since been unplugged
     * would put the window somewhere the user cannot reach it.
     */
    fun position(): Pair<Dp, Dp>? {
        val p = prefs ?: return null
        val x = p.getInt(KEY_X, Int.MIN_VALUE)
        val y = p.getInt(KEY_Y, Int.MIN_VALUE)
        return if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) null else x.dp to y.dp
    }

    fun save(width: Dp, height: Dp, x: Dp?, y: Dp?, maximized: Boolean, sidebarWidth: Dp) {
        val p = prefs ?: return
        runCatching {
            // A maximized window reports the screen rectangle, which would overwrite the size the
            // user actually chose; only the flag is stored in that case, so unmaximizing next
            // launch restores the earlier size.
            if (!maximized) {
                p.putInt(KEY_WIDTH, width.value.toInt())
                p.putInt(KEY_HEIGHT, height.value.toInt())
                x?.let { p.putInt(KEY_X, it.value.toInt()) }
                y?.let { p.putInt(KEY_Y, it.value.toInt()) }
            }
            p.putBoolean(KEY_MAXIMIZED, maximized)
            p.putInt(KEY_SIDEBAR, sidebarWidth.value.toInt())
            p.flush()
        }
    }

    private fun readDp(key: String, fallback: Dp): Dp {
        val value = prefs?.getInt(key, -1) ?: -1
        return if (value <= 0) fallback else value.dp
    }
}
