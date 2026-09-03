package my.cheysoff.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Naming the server an account created on this computer will sync through.
 *
 * ## Why this comes before the passphrase
 *
 * The address is checked against a live server here, and only then is a vault created. The order
 * matters because the two steps are not equally reversible: a mistyped address costs a correction,
 * whereas a vault is an Account Root Key that cannot be un-minted — and a vault created against a
 * server that turns out not to exist is an account no phone can ever be added to, which looks
 * exactly like a working app until the day somebody tries.
 *
 * ## Why an address is required at all
 *
 * In this direction the phone's half of the handshake cannot travel by QR — this computer has no
 * camera to read it with — so it goes through a rendezvous, and there has to be one. A user who has
 * no server is not stuck: they want the standalone option on the previous screen, which says
 * plainly what it costs.
 */
@Composable
fun NameServerScreen(
    url: String,
    busy: Boolean,
    message: String?,
    onUrlChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 620.dp).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "Where do your devices meet?",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Light,
        )
        Text(
            "Your notes are sealed on this computer before they are sent, and the server never " +
                "holds a key that opens them. What it does hold is the list of devices allowed on " +
                "the account — which is what lets you add your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MananaColors.BodyGrey,
        )

        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            singleLine = true,
            enabled = !busy,
            label = { Text("Server address") },
            placeholder = { Text("https://notes.example.com") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onContinue() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MananaColors.TitleGrey,
                unfocusedTextColor = MananaColors.TitleGrey,
                focusedBorderColor = MananaColors.IndigoTint,
                unfocusedBorderColor = MananaColors.Outline,
                focusedLabelColor = MananaColors.IndigoTint,
                unfocusedLabelColor = MananaColors.BodyGrey,
                cursorColor = MananaColors.IndigoTint,
            ),
        )

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MananaColors.Error)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onContinue,
                enabled = !busy && url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MananaColors.AccentIndigo,
                    disabledContainerColor = MananaColors.Outline,
                ),
            ) {
                Text(if (busy) "Checking…" else "Continue")
            }
            TextButton(onClick = onBack, enabled = !busy) {
                Text("Back", color = MananaColors.IndigoTint)
            }
        }
    }
}
