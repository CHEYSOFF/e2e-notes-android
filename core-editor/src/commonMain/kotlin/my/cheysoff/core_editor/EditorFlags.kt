package my.cheysoff.core_editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ComposeFoundationFlags

/**
 * Switches on Compose 1.12's styled editable text (`TextFieldBuffer.addStyle`).
 *
 * The flag is global and read when a text field is first composed, so each app calls [enable] once
 * at startup, before any composition: `Application.onCreate` on Android, ahead of `application {}`
 * on the desktop. Calling it again is harmless.
 */
object EditorFlags {
    @OptIn(ExperimentalFoundationApi::class)
    fun enable() {
        ComposeFoundationFlags.isBasicTextFieldStyledTextEnabled = true
    }
}
