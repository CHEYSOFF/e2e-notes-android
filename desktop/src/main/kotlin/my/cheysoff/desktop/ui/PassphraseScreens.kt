package my.cheysoff.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import my.cheysoff.desktop.vault.PassphrasePolicy

/**
 * Choosing the passphrase that will protect this vault.
 *
 * The explanatory line is not decoration. A user who has been typing a six-digit PIN into the phone
 * app is about to be asked for something else, and "because the file on this computer can be
 * copied and guessed at offline" is the entire reason. Saying nothing invites them to type six
 * digits and be refused by a rule that looks arbitrary.
 *
 * The passphrase is held in a [String] between keystrokes because that is what a Compose text field
 * gives, and a `String` cannot be zeroed. It is converted to a `CharArray` on submit and that array
 * is cleared by the controller. The residual `String` is a real limitation and not a pretend one —
 * closing it needs a text field that never materialises the value, which is a piece of work in its
 * own right and is noted here rather than quietly ignored.
 */
@Composable
fun CreatePassphraseScreen(
    busy: Boolean,
    message: String?,
    onBack: () -> Unit,
    onCreate: (CharArray, CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.widthIn(max = 560.dp).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Choose a passphrase",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
        )
        Text(
            "On your phone a PIN is enough, because the key behind it is locked into hardware " +
                "that cannot be copied. On a computer the file can be copied, and a PIN would be " +
                "guessed offline in about a minute. A passphrase is what closes that gap.",
            style = MaterialTheme.typography.bodyMedium,
            color = MananaColors.BodyGrey,
        )
        Text(
            "At least ${PassphrasePolicy.MIN_LENGTH} characters, and not digits alone. " +
                "Length is what matters most; a few unrelated words beat a short complicated one.",
            style = MaterialTheme.typography.bodySmall,
            color = MananaColors.BodyGrey,
        )

        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirmation,
            onValueChange = { confirmation = it },
            label = { Text("Repeat it") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "There is no way to recover this passphrase. Nothing about it leaves this computer, " +
                "so nobody — including whoever runs the sync server — can reset it for you.",
            style = MaterialTheme.typography.bodySmall,
            color = MananaColors.Warning,
        )

        if (message != null) {
            Text(message, color = MananaColors.Error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onCreate(passphrase.toCharArray(), confirmation.toCharArray()) },
                enabled = !busy && passphrase.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MananaColors.AccentIndigo),
            ) {
                Text("Create the vault")
            }
            TextButton(onClick = onBack, enabled = !busy) {
                Text("Back", color = MananaColors.IndigoTint)
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = MananaColors.IndigoTint,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** The passphrase prompt for a vault that already exists. */
@Composable
fun UnlockScreen(
    busy: Boolean,
    message: String?,
    credentialStoreName: String?,
    onUnlock: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.widthIn(max = 520.dp).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Welcome back.",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Light,
        )
        Text(
            "Your notes are encrypted on this computer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MananaColors.BodyGrey,
        )

        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                // Enter submits. On a desktop this is not a nicety: a single-field password form
                // that ignores the Return key reads as broken, and the person has already typed
                // the one thing the screen wanted before they find out they must also aim at a
                // button. Guarded on the same conditions as the button so the two cannot disagree
                // -- in particular it must not fire a second unlock while one is in flight.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                        !busy && passphrase.isNotEmpty()
                    ) {
                        onUnlock(passphrase.toCharArray())
                        true
                    } else {
                        false
                    }
                },
        )

        if (message != null) {
            Text(message, color = MananaColors.Error, style = MaterialTheme.typography.bodyMedium)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onUnlock(passphrase.toCharArray()) },
                enabled = !busy && passphrase.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MananaColors.AccentIndigo),
            ) {
                Text("Unlock")
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = MananaColors.IndigoTint,
                    strokeWidth = 2.dp,
                )
            }
        }

        if (credentialStoreName != null) {
            Text(
                "This computer can hold the key in $credentialStoreName so you do not retype " +
                    "this every launch. The passphrase still opens the vault anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MananaColors.BodyGrey,
            )
        }
    }
}

/**
 * A vault that exists and cannot be opened.
 *
 * There is deliberately no button on this screen. Every action a user would want here — "reset it",
 * "start over" — means writing a new `vault.json`, and the ARK inside the old one is, on a device
 * that has never paired, the only copy of the account key. An app that offers to fix this destroys
 * an account while looking helpful. The path forward is a paired device or a backup of the
 * directory, and the text says so.
 */
@Composable
fun DamagedScreen(reason: String, directory: String) {
    Column(
        modifier = Modifier.widthIn(max = 620.dp).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "This vault cannot be opened",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
            color = MananaColors.Error,
        )
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MananaColors.BodyGrey)
        Text(
            "Nothing has been changed or deleted. Your records are still in:\n$directory\n\n" +
                "If you have another device on this account, pair a fresh install with it. If you " +
                "have a backup of this folder from before the problem, restore the whole folder — " +
                "the files in it only work together.",
            style = MaterialTheme.typography.bodyMedium,
            color = MananaColors.BodyGrey,
        )
    }
}
