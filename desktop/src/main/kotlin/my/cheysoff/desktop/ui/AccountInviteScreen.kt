package my.cheysoff.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.desktop.pairing.InviteStep

/**
 * Adding a phone to the account this computer holds.
 *
 * ## Why the six digits are worded harder here than on `PairingScreen`
 *
 * They are doing a different job. On `PairingScreen` this computer is joining a phone's account:
 * the phone read this computer's key off a camera, a man in the middle is structurally impossible,
 * and the digits catch a mis-scan.
 *
 * Here the phone's key came back through the server, which is a channel somebody may control. If
 * they substituted their own key, the protocol agrees a secret with them and notices nothing — the
 * two screens simply show different numbers. So this comparison is the whole defence, the copy says
 * so plainly, and "they do not match" is the loud answer rather than the quiet one.
 *
 * That is also why the account key has not been sealed by the time this screen draws the digits.
 * `AccountInviteSession` has no method that seals; the object that does is minted by the
 * confirmation and by nothing else.
 */
@Composable
fun AccountInviteScreen(
    step: InviteStep,
    onConfirmSas: () -> Unit,
    onRejectSas: () -> Unit,
    onStartOver: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 620.dp).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "Add your phone",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Light,
        )

        when (step) {
            is InviteStep.Showing -> ShowingStep(step, onDone)
            is InviteStep.Confirming -> ConfirmingStep(step, onConfirmSas, onRejectSas)
            InviteStep.Finishing -> FinishingStep()
            is InviteStep.Done -> DoneStep(step, onDone)
            is InviteStep.Failed -> FailedStep(step, onStartOver, onDone)
        }
    }
}

@Composable
private fun ShowingStep(step: InviteStep.Showing, onCancel: () -> Unit) {
    Text(
        "On your phone, open Settings → Pair a device, choose \"Join from a computer\", and " +
            "point it at this code.",
        style = MaterialTheme.typography.bodyMedium,
        color = MananaColors.BodyGrey,
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MananaColors.Surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QrCode(step.code, modifier = Modifier.size(280.dp))

            Text(
                "Waiting for your phone… ${step.secondsRemaining}s",
                style = MaterialTheme.typography.bodyMedium,
                color = MananaColors.TitleGrey,
            )

            Text(
                if (step.secure) "via ${step.host}" else "via ${step.host} (not encrypted in transit)",
                style = MaterialTheme.typography.bodySmall,
                color = if (step.secure) MananaColors.BodyGrey else MananaColors.Warning,
                textAlign = TextAlign.Center,
            )

            step.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MananaColors.Warning,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    TextButton(onClick = onCancel) { Text("Not now", color = MananaColors.IndigoTint) }
}

@Composable
private fun ConfirmingStep(
    step: InviteStep.Confirming,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    Text(
        "Your phone answered and is showing six digits. Compare them carefully: this is the only " +
            "check that the answer came from your phone and not from something on the network " +
            "between you. Nothing has been sent yet.",
        style = MaterialTheme.typography.bodyMedium,
        color = MananaColors.BodyGrey,
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MananaColors.Surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                // Two groups of three: six digits read as one number are compared digit by digit
                // and misread; two groups are compared as two chunks.
                step.sas.chunked(3).joinToString("  "),
                fontSize = 44.sp,
                fontWeight = FontWeight.Light,
                color = MananaColors.TitleGrey,
            )
            Text(
                "Do these match your phone?",
                style = MaterialTheme.typography.bodyMedium,
                color = MananaColors.BodyGrey,
            )
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = MananaColors.AccentIndigo),
        ) {
            Text("They match — send the account key")
        }
        TextButton(onClick = onReject) {
            Text("They do not match", color = MananaColors.Warning)
        }
    }
}

@Composable
private fun FinishingStep() {
    Text(
        "Authorising your phone and sending the account key…",
        style = MaterialTheme.typography.bodyMedium,
        color = MananaColors.BodyGrey,
    )
}

@Composable
private fun DoneStep(step: InviteStep.Done, onDone: () -> Unit) {
    Text(
        if (step.enrolled) {
            "Your phone has the account key and is authorised on the server. Finish setting it up " +
                "there — it will ask for its own passphrase."
        } else {
            "Your phone has the account key. Finish setting it up there."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MananaColors.BodyGrey,
    )
    step.note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MananaColors.Warning)
    }
    Button(
        onClick = onDone,
        colors = ButtonDefaults.buttonColors(containerColor = MananaColors.AccentIndigo),
    ) {
        Text("Done")
    }
}

@Composable
private fun FailedStep(step: InviteStep.Failed, onStartOver: () -> Unit, onLeave: () -> Unit) {
    Text(step.message, style = MaterialTheme.typography.bodyMedium, color = MananaColors.Error)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onStartOver,
            colors = ButtonDefaults.buttonColors(containerColor = MananaColors.AccentIndigo),
        ) {
            Text("Start over")
        }
        TextButton(onClick = onLeave) { Text("Not now", color = MananaColors.IndigoTint) }
    }
}
