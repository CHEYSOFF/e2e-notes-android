package my.cheysoff.feature_auth.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.EncryptedNoteGrey
import my.cheysoff.core_ui.theme.IndigoTint
import my.cheysoff.core_ui.theme.NotesTheme
import my.cheysoff.core_ui.theme.WelcomeGrey
import my.cheysoff.feature_auth.R
import my.cheysoff.feature_auth.model.AuthMode
import my.cheysoff.feature_auth.model.AuthScreenIntent
import my.cheysoff.feature_auth.model.AuthScreenState

private val SheetSurface = Color(0xFF08080B)
private val SheetTitleGrey = Color(0xFFB6B6C2)
private val ErrorRed = Color(0xFFE0708A)

@Composable
fun AuthScreen(
    state: AuthScreenState,
    onIntentReceived: (AuthScreenIntent) -> Unit,
) {
    LaunchedEffect(Unit) { onIntentReceived(AuthScreenIntent.Initialize) }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val titleSize = (screenWidthDp * 0.175f).sp
    val secondarySize = (screenWidthDp * 0.042f).sp
    val moonSize = (screenWidthDp * 0.78f).dp

    val sheetUp = state.mode == AuthMode.ENTER_PIN ||
        state.mode == AuthMode.SET_PIN ||
        state.mode == AuthMode.CONFIRM_PIN

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBlack)
    ) {
        // Large crescent (native gold), top-right, bleeding off the corner — always present.
        Image(
            painter = painterResource(id = R.drawable.ic_crescent_moon_3d),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = moonSize * 0.065f, y = -(moonSize * 0.2f))
                .size(moonSize),
        )

        // Black -> transparent scrim protecting the status bar.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(170.dp)
                .background(
                    Brush.verticalGradient(
                        0f to AppBlack,
                        0.30f to AppBlack.copy(alpha = 0.75f),
                        1f to Color.Transparent,
                    )
                )
        )

        // Wordmark, top center.
        Text(
            text = "Mañana",
            color = Color(0xFF888888),
            fontWeight = FontWeight.Bold,
            fontSize = secondarySize,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .fillMaxWidth()
                .padding(top = 4.dp),
        )

        // Biometric landing. Also kept rendered as the backdrop BEHIND the keypad sheet when the
        // sheet was opened over it (ENTER_PIN reached via "Use PIN instead"), so dragging/dismissing
        // the sheet reveals the real screen underneath instead of black — and nothing pops back in.
        if (state.mode == AuthMode.BIOMETRIC ||
            (state.mode == AuthMode.ENTER_PIN && state.canDismissSheet)
        ) {
            BiometricLanding(
                titleSize = titleSize,
                secondarySize = secondarySize,
                // Only surface the error on the landing itself; when the sheet is open it owns the
                // message slot and would otherwise show it twice.
                error = state.error.takeIf { state.mode == AuthMode.BIOMETRIC },
                onIntentReceived = onIntentReceived,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            )
        }

        // When the sheet can be dismissed, system-back and a tap above the sheet return to the
        // prior surface (biometric landing, or Create-PIN from Confirm).
        if (sheetUp && state.canDismissSheet) {
            BackHandler { onIntentReceived(AuthScreenIntent.DismissSheet) }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onIntentReceived(AuthScreenIntent.DismissSheet) }
            )
        }

        // Slide-up keypad sheet (rises on launch for set/confirm, on "Use PIN instead" for unlock).
        AnimatedVisibility(
            visible = sheetUp,
            enter = slideInVertically(animationSpec = tween(420)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(animationSpec = tween(280)) { it } + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            KeypadSheet(state = state, onIntentReceived = onIntentReceived)
        }
    }
}

@Composable
private fun BiometricLanding(
    titleSize: androidx.compose.ui.unit.TextUnit,
    secondarySize: androidx.compose.ui.unit.TextUnit,
    error: String?,
    onIntentReceived: (AuthScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = WelcomeGrey, fontWeight = FontWeight.Light)) {
                    append("Welcome")
                }
                append("\n")
                withStyle(SpanStyle(color = IndigoTint, fontWeight = FontWeight.Medium)) {
                    append("back.")
                }
            },
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = titleSize,
                lineHeight = titleSize * 0.96f,
                letterSpacing = (-1.4).sp,
            ),
        )
        Text(
            // Biometric failures (unavailable key, unusable wrap) previously set state.error with
            // no slot to render it here, leaving the Unlock button looking dead. Swap the
            // reassurance line for the error so the PIN fallback is discoverable.
            text = error ?: "Your notes are encrypted on this device.",
            color = if (error != null) ErrorRed else EncryptedNoteGrey,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = secondarySize),
            modifier = Modifier.padding(top = 16.dp),
        )

        Spacer(modifier = Modifier.height(30.dp))

        Button(
            onClick = {
                (context as? FragmentActivity)?.let {
                    onIntentReceived(AuthScreenIntent.BiometricUnlock(it))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentIndigo,
                contentColor = Color(0xFFE8E6F5),
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.fingerprint),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(0xFFE8E6F5)),
                    modifier = Modifier
                        .size((screenWidthDp * 0.075f).dp)
                        .padding(end = 10.dp),
                )
                Text(
                    text = "Unlock",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = (screenWidthDp * 0.05f).sp),
                )
            }
        }

        TextButton(
            onClick = { onIntentReceived(AuthScreenIntent.UsePinInstead) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                text = "Use PIN instead",
                color = Color(0xFF777777),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = (screenWidthDp * 0.038f).sp),
            )
        }
    }
}

@Composable
private fun KeypadSheet(
    state: AuthScreenState,
    onIntentReceived: (AuthScreenIntent) -> Unit,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

    val title = when (state.mode) {
        AuthMode.SET_PIN -> if (state.isMigration) "Secure your notes" else "Create your PIN"
        AuthMode.CONFIRM_PIN -> "Confirm your PIN"
        else -> "Enter your PIN"
    }
    val status: Pair<String, Color>? = when {
        state.lockoutSecondsRemaining > 0 -> "Try again in ${state.lockoutSecondsRemaining}s" to ErrorRed
        state.error != null -> state.error to ErrorRed
        else -> null
    }
    val keysEnabled = !state.isLoading && state.lockoutSecondsRemaining == 0

    // Drag-to-dismiss: follow the finger down, release past the threshold to dismiss (else snap back).
    val scope = rememberCoroutineScope()
    // Live drag offset is a plain synchronous Float (updated directly in the drag callback) so drag
    // events can't race the release animation; only the release spring uses a coroutine.
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 130.dp.toPx() }
    // Reset the drag offset whenever the sheet's mode changes (e.g. Confirm -> Create keeps the
    // sheet composed, so the offset would otherwise stay where the drag left it).
    // Realign the offset only WITHIN sheet modes (e.g. Confirm -> Create, which keeps the sheet
    // composed). When the mode leaves to a non-sheet mode (e.g. -> BIOMETRIC on drag-dismiss) the
    // sheet is animating OUT from wherever the drag left it; snapping the offset back to 0 there
    // makes it jump to the top before AnimatedVisibility slides it out ("snap to top then bottom").
    LaunchedEffect(state.mode) {
        val sheetMode = state.mode == AuthMode.ENTER_PIN ||
            state.mode == AuthMode.SET_PIN ||
            state.mode == AuthMode.CONFIRM_PIN
        if (sheetMode && dragOffsetY != 0f) {
            animate(dragOffsetY, 0f) { value, _ -> dragOffsetY = value }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .then(
                if (state.canDismissSheet) Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                        },
                        onDragEnd = {
                            if (dragOffsetY > dismissThresholdPx) {
                                onIntentReceived(AuthScreenIntent.DismissSheet)
                            } else {
                                scope.launch {
                                    animate(dragOffsetY, 0f) { value, _ -> dragOffsetY = value }
                                }
                            }
                        },
                    )
                } else Modifier
            )
            .clip(sheetShape)
            .background(SheetSurface)
            // systemBarsPadding() here also applied the STATUS BAR inset to the sheet's top edge —
            // but the sheet is bottom-anchored, so that edge sits mid-screen with no status bar to
            // avoid. The inset became dead sheet surface between the rounded top and the grabber
            // (most visible on first launch, where the sheet's near-black surface is invisible
            // against the black backdrop and the gap just reads as space above the handle).
            // Keep the bottom (navigation bar) and horizontal (cutout/gesture) insets unchanged.
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            )
            .padding(horizontal = 28.dp)
            .padding(top = 16.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Grabber — tappable to dismiss when there's somewhere to return to.
        Box(
            modifier = Modifier
                .then(
                    if (state.canDismissSheet) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onIntentReceived(AuthScreenIntent.DismissSheet) } else Modifier
                )
                .padding(horizontal = 48.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF2A2A32))
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            color = SheetTitleGrey,
            fontWeight = FontWeight.Medium,
            fontSize = (screenWidthDp * 0.043f).sp,
        )

        val subLineSize = (screenWidthDp * 0.034f).sp
        // Constant-height slot: the status text/spinner appears and disappears WITHOUT resizing the
        // sheet (otherwise the bottom-anchored sheet grows upward and the grabber appears to jump).
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier.height(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // After the PIN is entered, show that it's being verified.
                state.isLoading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = IndigoTint,
                    )
                    Text(
                        text = if (state.mode == AuthMode.CONFIRM_PIN) "Setting up…" else "Checking…",
                        color = SheetTitleGrey,
                        fontSize = subLineSize,
                    )
                }

                status != null -> Text(
                    text = status.first,
                    color = status.second,
                    fontSize = subLineSize,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        PinDots(filled = state.pinLength, total = state.pinMaxLength)

        // Approved 72px dots -> keypad gap.
        Spacer(modifier = Modifier.height((screenWidthDp * 0.184f).dp))

        PinPad(
            onDigit = { onIntentReceived(AuthScreenIntent.Digit(it)) },
            onBackspace = { onIntentReceived(AuthScreenIntent.Backspace) },
            enabled = keysEnabled,
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AuthScreenPreview() {
    NotesTheme {
        AuthScreen(AuthScreenState(mode = AuthMode.ENTER_PIN, pinLength = 3), {})
    }
}
