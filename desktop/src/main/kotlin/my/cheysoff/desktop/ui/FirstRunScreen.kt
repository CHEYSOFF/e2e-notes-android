package my.cheysoff.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The first-run choice.
 *
 * ## Why pairing leads and standalone does not
 *
 * The Account Root Key is created once, on the first device, and reaches every other device by
 * pairing. A desktop that mints its own ARK has not joined the user's account — it has started a
 * second one. The two never merge and cannot be merged afterwards: records are sealed under keys
 * derived from the ARK, the two `accountId`s do not even name the same bucket on the server, and
 * neither half's plaintext is recoverable from the other's key. `AccountRootKey.generateArk`'s KDoc
 * spells this out.
 *
 * A user cannot be expected to know that, and "the notes on my laptop never showed up on my phone"
 * is what the mistake feels like from outside. So the layout is the argument: pairing is the card,
 * standalone is a text link, and taking the link costs a dialog that says what it does in the
 * plainest words available. This is not a nag — it is the only moment at which the choice is
 * reversible, because after the ARK exists there is nothing to undo.
 *
 * Pairing fills the seam it was always going to fill: `DesktopVault.setUp(passphrase,
 * AccountOrigin.PAIRED, ark)`, which already existed and already refused to mint a key of its own.
 * The ARK reaches it from the phone, through the rendezvous — see `PairingScreen`.
 */
@Composable
fun FirstRunScreen(
    onPair: () -> Unit,
    onUseStandalone: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.widthIn(max = 620.dp).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Manana", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light)
        Text(
            "Set up this computer.",
            style = MaterialTheme.typography.titleMedium,
            color = MananaColors.BodyGrey,
        )

        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MananaColors.Surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Pair with your phone",
                    style = MaterialTheme.typography.titleLarge,
                    color = MananaColors.TitleGrey,
                )
                Text(
                    "Your account key already exists on your phone. Pairing copies it here, so " +
                        "the same notes open on both.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MananaColors.BodyGrey,
                )
                Button(
                    onClick = onPair,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MananaColors.AccentIndigo,
                        disabledContainerColor = MananaColors.Outline,
                    ),
                ) {
                    Text("Pair with my phone")
                }
            }
        }

        TextButton(onClick = { confirming = true }) {
            Text("Use this computer on its own", color = MananaColors.IndigoTint)
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = MananaColors.Surface,
            title = { Text("This starts a separate account", color = MananaColors.TitleGrey) },
            text = {
                Text(
                    "Notes you write here will never appear on your phone, and notes on your " +
                        "phone will never appear here. The two accounts cannot be joined later — " +
                        "not by pairing, and not by any recovery step.\n\n" +
                        "Choose this only if this computer is the only device you will use.",
                    color = MananaColors.BodyGrey,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onUseStandalone()
                }) {
                    Text("Start a separate account", color = MananaColors.Warning)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text("Cancel", color = MananaColors.IndigoTint)
                }
            },
        )
    }
}
