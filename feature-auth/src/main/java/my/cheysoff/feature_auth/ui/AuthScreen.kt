package my.cheysoff.feature_auth.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.EncryptedNoteGrey
import my.cheysoff.core_ui.theme.IndigoTint
import my.cheysoff.core_ui.theme.NotesTheme
import my.cheysoff.core_ui.theme.WelcomeGrey
import my.cheysoff.feature_auth.R
import my.cheysoff.feature_auth.model.AuthScreenIntent
import my.cheysoff.feature_auth.model.AuthScreenState

@Composable
fun AuthScreen(
    state: AuthScreenState,
    onIntentReceived: (AuthScreenIntent) -> Unit,
) {
    // Size the hero title relative to screen width so it keeps its proportion on large devices.
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val titleSize = (screenWidthDp * 0.175f).sp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBlack)
    ) {
        // Large emoji-style crescent (native gold), top-right, bleeding off the corner.
        Image(
            painter = painterResource(id = R.drawable.ic_crescent_moon),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = (-30).dp)
                .size(220.dp),
        )

        // Black -> transparent scrim over the top: darkens the moon's top + protects status bar.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    Brush.verticalGradient(
                        0f to AppBlack,
                        0.30f to AppBlack.copy(alpha = 0.75f),
                        1f to Color.Transparent,
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            // Wordmark, plain centered text at the top.
            Text(
                text = "Mañana",
                color = Color(0xFF888888),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                style = MaterialTheme.typography.titleSmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )

            Spacer(modifier = Modifier.weight(1f))

            // Big editorial title, left-aligned, with the indigo "back."
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
                text = "Your notes are encrypted on this device.",
                color = EncryptedNoteGrey,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                modifier = Modifier.padding(top = 16.dp),
            )

            Spacer(modifier = Modifier.height(30.dp))

            AuthActions(state, onIntentReceived)

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AuthActions(
    state: AuthScreenState,
    onIntentReceived: (AuthScreenIntent) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.areBiometricsEnabled) {
            Button(
                onClick = {
                    (context as? FragmentActivity)?.let { activity ->
                        onIntentReceived(AuthScreenIntent.BiometricsLoginClickIntent(activity))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
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
                            .size(20.dp)
                            .padding(end = 8.dp),
                    )
                    Text(text = "Unlock", style = MaterialTheme.typography.titleSmall)
                }
            }
        }

        // Quiet PIN fallback (wired to the PIN intent; PIN flow itself is a later feature).
        TextButton(onClick = { onIntentReceived(AuthScreenIntent.PinLoginClickIntent) }) {
            Text(
                text = "Use PIN instead",
                color = Color(0xFF777777),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AuthScreenPreview() {
    NotesTheme(darkTheme = true) {
        AuthScreen(AuthScreenState(areBiometricsEnabled = true), {})
    }
}
