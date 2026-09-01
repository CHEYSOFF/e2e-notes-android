package my.cheysoff.ios.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.ios.theme.Manana

/**
 * The lock screen: a PIN keypad.
 *
 * ## Why a keypad and not a text field
 *
 * The same reason the Android app has one. A `TextField` on iOS brings up the system keyboard,
 * which means the PIN passes through the keyboard extension the user has installed, gets offered to
 * autofill, and may be learned by the predictive-text model. A keypad drawn by the app avoids all
 * three, and it is also the affordance people expect from a lock screen.
 *
 * ## What is missing, and is not pretending otherwise
 *
 * **Biometrics.** The Android app unlocks with a fingerprint and this does not. The iOS equivalent
 * is a Keychain access-control flag plus `LAContext`, and its edge cases -- re-enrolment
 * invalidating the item, biometry lockout, the passcode fallback -- are exactly the kind of thing
 * that must be seen to work and seen to fail. Adding it unverified would be adding an unlock path
 * nobody has watched do either.
 *
 * **Attempt throttling.** Android's `SecureUnlockManager` backs off after repeated wrong PINs. This
 * does not, and the omission is bounded rather than free: PBKDF2 at 210,000 rounds already costs
 * an attacker a few hundred milliseconds per guess *through this UI*, and an attacker who can do
 * better than that is one who has extracted the Keychain item and is not typing into this screen at
 * all. Worth adding; not worth pretending is a defence against the case that matters.
 */
@Composable
fun UnlockScreen(
    isSetup: Boolean,
    error: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // `safeDrawing` rather than a hardcoded top padding: the notch, the Dynamic Island and
            // the home indicator are all different sizes, and this is one of the places a layout
            // written without a device in front of it goes wrong.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (isSetup) "Choose a PIN." else "Welcome back.",
            color = Manana.WelcomeGrey,
            fontSize = 34.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isSetup) {
                "It is the only way in. Nothing here can recover it for you."
            } else {
                "Your notes are encrypted on this device."
            },
            color = Manana.EncryptedNoteGrey,
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(40.dp))
        PinDots(length = pin.length, capacity = PIN_LENGTH)

        Spacer(Modifier.height(16.dp))
        Text(
            // The height is held whether or not there is anything to say, so the keypad does not
            // jump up and down between a failed attempt and the next one.
            text = when {
                busy -> "Checking…"
                error != null -> error
                else -> " "
            },
            color = if (error != null && !busy) Manana.IndigoTint else Manana.BodyGrey,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(24.dp))
        Keypad(
            enabled = !busy,
            onDigit = { digit ->
                if (pin.length < PIN_LENGTH) {
                    pin += digit
                    if (pin.length == PIN_LENGTH) {
                        onSubmit(pin)
                        // Cleared as soon as it is handed over: a PIN left on screen is a PIN
                        // visible in the app switcher's snapshot.
                        pin = ""
                    }
                }
            },
            onBackspace = { pin = pin.dropLast(1) },
        )
    }
}

/** How many digits a PIN is. Matches the Android app; changing it invalidates no stored wrap. */
private const val PIN_LENGTH = 6

@Composable
private fun PinDots(length: Int, capacity: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(capacity) { index ->
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (index < length) Manana.IndigoTint else Manana.Outline)
            )
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { digit -> Key(digit.toString(), enabled) { onDigit(digit) } }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Spacer(Modifier.width(72.dp))
            Key("0", enabled) { onDigit('0') }
            Key("⌫", enabled, onBackspace)
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Manana.Surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) Manana.TitleGrey else Manana.EncryptedNoteGrey,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
        )
    }
}
