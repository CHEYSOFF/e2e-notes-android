package my.cheysoff.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_pairing.qr.QrCodes
import my.cheysoff.desktop.pairing.PairingStep

/**
 * Pairing, on the laptop.
 *
 * ## The shape of the screen is the shape of the protocol
 *
 * Three steps, and each is a thing the user does rather than a thing that happens to them: name the
 * server, hold the phone up to this QR code, compare six digits. The last one is the only place a
 * pairing is committed, and it is a question with two answers rather than a notice with an OK
 * button, because "they do not match" has to be as easy to say as "they do."
 *
 * ## Why the QR is drawn rather than rasterised
 *
 * A [Canvas] with one `drawRect` per module, not a bitmap scaled up. The Android side allocates an
 * `android.graphics.Bitmap` and draws it with `FilterQuality.None` for the same end — hard-edged
 * modules — but a desktop window is resizable and a bitmap sized for one window width is soft at
 * the next. Drawing at layout size means the modules are always exactly as crisp as the display
 * allows, which is what the phone's camera has to resolve.
 *
 * The code is light-on-dark, matching the phone: every screen in this app is black, and a sudden
 * white sheet is jarring. `QrCodes.decodeLuminance` runs an inverted second pass precisely so that
 * a Mañana code is readable by Mañana.
 */
@Composable
fun PairingScreen(
    step: PairingStep,
    onAddressChange: (String) -> Unit,
    onStart: () -> Unit,
    onConfirmSas: () -> Unit,
    onRejectSas: () -> Unit,
    onStartOver: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = 620.dp).padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Pair with your phone", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light)

        when (step) {
            is PairingStep.Address -> AddressStep(step, onAddressChange, onStart, onBack)
            is PairingStep.Waiting -> WaitingStep(step, onStartOver)
            is PairingStep.Confirming -> ConfirmStep(step, onConfirmSas, onRejectSas)
            is PairingStep.Failed -> FailedStep(step, onStartOver, onBack)
            // The caller swaps this screen out the moment the pairing is confirmed; the branch is
            // explicit so a new step cannot be added without deciding what it draws.
            PairingStep.Confirmed -> Unit
        }
    }
}

@Composable
private fun AddressStep(
    step: PairingStep.Address,
    onAddressChange: (String) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        "Your phone sends its account key through a server you run. The server only ever holds " +
            "the sealed bundle — it has no key that opens it, and it deletes the bundle the " +
            "moment this computer collects it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MananaColors.BodyGrey,
    )

    OutlinedTextField(
        value = step.url,
        onValueChange = onAddressChange,
        singleLine = true,
        label = { Text("Server address") },
        placeholder = { Text("https://notes.example.com") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onStart() }),
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

    step.message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MananaColors.Error)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onStart,
            enabled = step.url.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MananaColors.AccentIndigo,
                disabledContainerColor = MananaColors.Outline,
            ),
        ) {
            Text("Show the pairing code")
        }
        TextButton(onClick = onBack) { Text("Back", color = MananaColors.IndigoTint) }
    }
}

@Composable
private fun WaitingStep(step: PairingStep.Waiting, onStartOver: () -> Unit) {
    Text(
        "On your phone, open Settings → Pair a device, choose \"This phone has my notes\", and " +
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

            // The host is stated because it is the address the phone is about to be asked to send
            // to, and the phone shows the same string before it sends. Two screens agreeing on a
            // host is the check; a screen that never named it would make that check impossible.
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

    TextButton(onClick = onStartOver) { Text("Cancel", color = MananaColors.IndigoTint) }
}

@Composable
private fun ConfirmStep(step: PairingStep.Confirming, onConfirm: () -> Unit, onReject: () -> Unit) {
    Text(
        "Your phone should be showing the same six digits. Nothing has been saved yet.",
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
                // Spaced in two groups of three: six digits read as one number are compared
                // digit by digit and misread; two groups are compared as two chunks.
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

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(containerColor = MananaColors.AccentIndigo),
        ) {
            Text("They match")
        }
        // As easy to reach as the confirm button, and coloured as the warning it is. A "no" here is
        // the only signal that the wrong device answered, and burying it would waste the only
        // check a person can actually perform.
        TextButton(onClick = onReject) {
            Text("They do not match", color = MananaColors.Warning)
        }
    }
}

@Composable
private fun FailedStep(step: PairingStep.Failed, onStartOver: () -> Unit, onBack: () -> Unit) {
    Text(step.message, style = MaterialTheme.typography.bodyMedium, color = MananaColors.Error)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onStartOver,
            colors = ButtonDefaults.buttonColors(containerColor = MananaColors.AccentIndigo),
        ) {
            Text("Start over")
        }
        TextButton(onClick = onBack) { Text("Back", color = MananaColors.IndigoTint) }
    }
}

/**
 * One QR symbol, drawn module by module at whatever size the layout gives it.
 *
 * The matrix already contains its four-module quiet zone (`QrCodes` sets `MARGIN`), so nothing is
 * added around it here — a second margin would shrink the modules for no gain, and a *missing* one
 * is what makes a code unreadable at the edges.
 *
 * `size / modules` is deliberately not rounded: a fractional module width draws sub-pixel edges
 * that anti-alias slightly, which a camera handles far better than the alternative of rounding down
 * and leaving a bright unpainted strip along two sides of the symbol.
 */
@Composable
internal fun QrCode(code: String, modifier: Modifier = Modifier) {
    // `remember(code)`: encoding runs the symbol back through the decoder at three scales to catch
    // the ~0.6% of payloads that encode into something unreadable (see QrCodes), which is far too
    // much work to repeat on every recomposition — and the countdown recomposes this once a second.
    val matrix = remember(code) { QrCodes.encode(code) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            // The same two colours the phone's own QR uses, so a person holding both sees one
            // design rather than two apps. A "dark" module is drawn light: see QrCode's KDoc.
            .background(MananaColors.Surface),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val module = size.minDimension / matrix.size
            for (y in 0 until matrix.size) {
                for (x in 0 until matrix.size) {
                    if (!matrix[x, y]) continue
                    drawRect(
                        color = QrModule,
                        topLeft = Offset(x * module, y * module),
                        size = Size(module, module),
                    )
                }
            }
        }
    }
}

/** The colour a dark module is painted. Matches `feature-pairing`'s `PairingScreen`. */
private val QrModule = Color(0xFFEDEDED)
