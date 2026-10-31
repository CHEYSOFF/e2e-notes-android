package my.cheysoff.desktop.ui

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap

/**
 * The app's icon for its windows (title bar, taskbar, Alt+Tab): the Android launcher's crescent on
 * its indigo bloom, as `resources/manana_icon.png`. The installer's icon (`icons/manana.ico`) is
 * the same art. Null if the resource is missing, so the window falls back to the default.
 */
val MananaIcon: Painter? by lazy {
    Thread.currentThread().contextClassLoader?.getResourceAsStream("manana_icon.png")
        ?.use { BitmapPainter(loadImageBitmap(it)) }
}
