package my.cheysoff.core_editor

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertTrue

class StyledTextSmokeTest {

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun `styled text survives an edit`() = runComposeUiTest {
        EditorFlags.enable()
        val state = TextFieldState()
        setContent { BasicTextField(state) }
        state.edit {
            append("ab")
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 2)
        }
        // A programmatic edit, not OS input: it goes through the same buffer a typed char does.
        state.edit { append("c") }
        waitForIdle()
        // `edit` returns Unit, so the answer is carried out through a local.
        var found = false
        state.edit { found = getSpanStyles(TextRange(0, 2)).isNotEmpty() }
        assertTrue(found, "bold span lost after edit")
    }
}
