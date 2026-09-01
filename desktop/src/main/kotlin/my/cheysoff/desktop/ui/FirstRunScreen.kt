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
 * ## Three answers, because there are three situations
 *
 * The Account Root Key is created once, on the first device, and reaches every other device by
 * pairing. What a first run has to establish is **which device that first one is**, and until this
 * screen offered both directions it could only ever be the phone — which left a person who set the
 * desktop up first with no option but to start a second account.
 *
 *  - **Pair with my phone.** The account already exists on the phone; this computer joins it. This
 *    is listed first and given the larger card because it is both the commonest case and the
 *    stronger handshake: the phone reads this computer's key off a camera, so a man in the middle
 *    is structurally impossible. See `PairingScreen`.
 *  - **Start a new account here.** This computer is the first device, and a phone joins it
 *    afterwards. It needs a server address, because the phone's answer has nowhere else to travel;
 *    the exchange that follows is authenticated by six digits a person compares, which is a weaker
 *    guarantee than the one above and is stated as such where it applies (`AccountInviteScreen`).
 *  - **Use this computer on its own.** No server, no other device, ever.
 *
 * ## Why standalone is a text link and costs a dialog
 *
 * A desktop that mints its own ARK and never names a server has not joined the user's account — it
 * has started a second one. The two never merge and cannot be merged afterwards: records are sealed
 * under keys derived from the ARK, the two `accountId`s do not even name the same bucket on a
 * server, and neither half's plaintext is recoverable from the other's key.
 * `AccountRootKey.generateArk`'s KDoc spells this out.
 *
 * A user cannot be expected to know that, and "the notes on my laptop never showed up on my phone"
 * is what the mistake feels like from outside. So the layout is the argument, and taking the link
 * costs a dialog that says what it does in the plainest words available. This is not a nag — it is
 * the only moment at which the choice is reversible, because after the ARK exists there is nothing
 * to undo. **The middle option is not an escape from that dialog**: it also mints a fresh ARK, and
 * it is a different answer only because a phone can afterwards be admitted to the account it
 * creates.
 */
@Composable
fun FirstRunScreen(
    onPair: () -> Unit,
    onCreateAccountHere: () -> Unit,
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
                    "Choose this if your notes are already on your phone. Pairing copies the " +
                        "account key here, so the same notes open on both.",
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

        Card(
            colors = CardDefaults.cardColors(containerColor = MananaColors.Surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Start a new account here",
                    style = MaterialTheme.typography.titleLarge,
                    color = MananaColors.TitleGrey,
                )
                Text(
                    "Choose this if this computer is your first device. It creates the account " +
                        "key here, and you can add your phone to it straight afterwards. You will " +
                        "need the address of the server your devices sync through.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MananaColors.BodyGrey,
                )
                Button(
                    onClick = onCreateAccountHere,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MananaColors.AccentIndigo,
                        disabledContainerColor = MananaColors.Outline,
                    ),
                ) {
                    Text("Start a new account")
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
