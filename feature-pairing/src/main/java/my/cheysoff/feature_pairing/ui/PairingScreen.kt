package my.cheysoff.feature_pairing.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.painter.BitmapPainter
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.IndigoTint
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.OutlineDark
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.feature_pairing.qr.QrCodes
import my.cheysoff.feature_pairing.qr.rememberQrImageBitmap

/** Matches the settings cards and note cards: between Radii.medium and Radii.large. */
private val CardRadius = 20.dp

/** The one non-palette colour on this screen. Same value AuthScreen uses for a failed unlock. */
private val ErrorRed = Color(0xFFE0708A)

/**
 * Pair another device.
 *
 * ## FLAG_SECURE
 *
 * Both halves of this screen put key material on the display: QR1 is an ephemeral public key (not
 * secret, but it identifies a live session) and QR2 contains the account root key sealed under a
 * key an attacker with a screenshot could not derive — but a screenshot of *both* codes plus a
 * screenshot of the SAS is a far better starting point than anyone should be handed. The
 * screenshot and the Recents thumbnail are the two ways a phone leaks a screen without the user
 * doing anything, so both are closed here.
 *
 * `MainActivity` already sets `setRecentsScreenshotEnabled(false)` on API 33+ and falls back to
 * `FLAG_SECURE` below that, which covers the thumbnail everywhere and screenshots on 31/32. This
 * screen adds `FLAG_SECURE` unconditionally, so screenshots and screen recording are blocked here
 * on every version.
 *
 * The flag is cleared on the way out **only on API 33+**. On 31 and 32 `MainActivity` set the same
 * flag for the whole app and clearing it here would silently turn note screenshots into a leak of
 * the Recents thumbnail — the exact thing MainActivity's fallback exists to prevent.
 */
@Composable
fun PairingScreen(
    state: PairingScreenState,
    onIntent: (PairingIntent) -> Unit,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val activity = context as? Activity

    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    // --- camera permission -------------------------------------------------------------------
    // Requested lazily: the app has never asked for a permission before, and asking on entry to a
    // screen the user may only be reading would spend that first impression on nothing. The
    // request fires when a step actually needs the camera.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // shouldShowRequestPermissionRationale is false in two different situations: before the
        // first ask, and after the user has refused permanently. Checking it *after* a refusal is
        // therefore the only reliable way Android exposes "don't ask again" -- there is no API
        // that reports it directly.
        val permanentlyDenied = !granted && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        onIntent(PairingIntent.CameraPermissionChanged(granted, permanentlyDenied))
    }

    val needsCamera = state.stage is PairingStage.ScanningOffer ||
        state.stage is PairingStage.ScanningSeal

    LaunchedEffect(needsCamera, state.cameraPermission) {
        if (!needsCamera) return@LaunchedEffect
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        when {
            alreadyGranted && state.cameraPermission != CameraPermission.Granted ->
                onIntent(PairingIntent.CameraPermissionChanged(granted = true, permanentlyDenied = false))

            !alreadyGranted && state.cameraPermission == CameraPermission.Unknown ->
                permissionLauncher.launch(Manifest.permission.CAMERA)

            else -> Unit
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { PairingTopBar(onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + spacing.l,
                )
                .padding(horizontal = spacing.screenHorizontal),
        ) {
            PairingHeader()

            if (!state.available) {
                UnavailableCard()
                return@Column
            }

            when (val stage = state.stage) {
                PairingStage.ChoosingRole -> RoleChooser(
                    canShareAccount = state.canShareAccount,
                    onChoose = { onIntent(PairingIntent.RoleChosen(it)) },
                )

                is PairingStage.ShowingOffer -> ShowCodeStep(
                    title = "Show this to your other phone",
                    body = "On the phone that already has your notes, choose “Pair a " +
                        "device” and point its camera at this code.",
                    code = stage.code,
                    secondsRemaining = stage.secondsRemaining,
                    sas = null,
                    primaryLabel = "They've scanned it",
                    onPrimary = { onIntent(PairingIntent.OfferShown) },
                    onStartOver = { onIntent(PairingIntent.StartOver) },
                )

                is PairingStage.ScanningOffer -> ScanStep(
                    title = "Scan the new phone's code",
                    body = "Point this camera at the code showing on the phone you are adding.",
                    hint = stage.lastHint,
                    secondsRemaining = null,
                    permission = state.cameraPermission,
                    onCode = { onIntent(PairingIntent.CodeScanned(it)) },
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { context.openAppSettings() },
                    onStartOver = { onIntent(PairingIntent.StartOver) },
                )

                is PairingStage.ScanningSeal -> ScanStep(
                    title = "Now scan their reply",
                    body = "The other phone is showing a second code. Point this camera at it.",
                    hint = stage.lastHint,
                    secondsRemaining = stage.secondsRemaining,
                    permission = state.cameraPermission,
                    onCode = { onIntent(PairingIntent.CodeScanned(it)) },
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { context.openAppSettings() },
                    onStartOver = { onIntent(PairingIntent.StartOver) },
                )

                is PairingStage.ShowingSeal -> ShowCodeStep(
                    title = "Show this back to the new phone",
                    body = "This code carries your account key, sealed so that only the phone " +
                        "whose code you just scanned can open it.",
                    code = stage.code,
                    secondsRemaining = stage.secondsRemaining,
                    sas = stage.sas,
                    primaryLabel = "They've scanned it",
                    onPrimary = { onIntent(PairingIntent.SealShown) },
                    onStartOver = { onIntent(PairingIntent.StartOver) },
                )

                is PairingStage.Confirming -> ConfirmStep(
                    sas = stage.sas,
                    onMatch = { onIntent(PairingIntent.SasConfirmed) },
                    onMismatch = { onIntent(PairingIntent.SasRejected) },
                )

                is PairingStage.Finished -> FinishedCard(
                    role = stage.role,
                    onDone = onBack,
                )

                is PairingStage.Failed -> FailedCard(
                    message = stage.message,
                    onStartOver = { onIntent(PairingIntent.StartOver) },
                )
            }
        }
    }
}

// ── Building blocks ─────────────────────────────────────────────────────────

/** Back arrow only, matching Trash's and the editor's bars rather than a titled Material one. */
@Composable
private fun PairingTopBar(onBack: () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal - 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBackIos,
                contentDescription = "Back",
                tint = TitleGrey,
            )
        }
    }
}

/** The same two-tone display header the notes list, settings and Trash all use. */
@Composable
private fun PairingHeader() {
    val sw = LocalConfiguration.current.screenWidthDp
    val headerSize = (sw * 0.092f).sp
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = TitleGrey, fontWeight = FontWeight.Light)) {
                append("Pair a")
            }
            append("\n")
            withStyle(SpanStyle(color = IndigoTint, fontWeight = FontWeight.Medium)) {
                append("device.")
            }
        },
        style = MaterialTheme.typography.titleLarge.copy(
            fontSize = headerSize,
            lineHeight = headerSize * 1.05f,
            letterSpacing = (-0.6).sp,
        ),
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 18.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(SurfaceDark)
            .padding(18.dp),
    ) { content() }
}

@Composable
private fun CardTitle(text: String) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = text,
        color = TitleGrey,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = (sw * 0.045f).sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

@Composable
private fun CardBody(text: String, color: Color = BodyGrey) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = (sw * 0.035f).sp,
            lineHeight = (sw * 0.05f).sp,
        ),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val sw = LocalConfiguration.current.screenWidthDp
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(top = 0.dp),
        shape = RoundedCornerShape(percent = 50),
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentIndigo,
            contentColor = Color(0xFFE8E6F5),
            disabledContainerColor = Color(0xFF241C56),
            disabledContentColor = Color(0xFF8A87A0),
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = (sw * 0.045f).sp),
        )
    }
}

@Composable
private fun StartOverButton(onClick: () -> Unit) {
    val sw = LocalConfiguration.current.screenWidthDp
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Start over",
            color = Color(0xFF777777),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = (sw * 0.038f).sp),
        )
    }
}

/**
 * The `PairingKeyMaterial.isBound` gate. Says exactly what is missing rather than pretending the
 * feature is broken.
 *
 * Unreachable in a build where the sync key hierarchy is bound, which is every shipped build since
 * `SecureUnlockArkStore` replaced the placeholder. Kept as the backstop for a build where it is
 * not.
 */
@Composable
private fun UnavailableCard() {
    Card {
        CardTitle("Not available in this build")
        CardBody(
            "Pairing shares the key that encrypts your synced notes, and this build has no " +
                "way to create or store that key. Everything else here is ready and waiting " +
                "for it."
        )
    }
}

@Composable
private fun RoleChooser(canShareAccount: Boolean, onChoose: (PairingRole) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            CardTitle("Which phone is this?")
            CardBody(
                "Pairing takes two steps and both phones need to be in front of you. Nothing " +
                    "is sent anywhere — the two phones only look at each other's screens."
            )
        }
        RoleCard(
            title = "This phone has my notes",
            body = "Scan the new phone's code, then show it the reply.",
            enabled = canShareAccount,
            disabledNote = "This phone has no account key to share yet.",
            onClick = { onChoose(PairingRole.HasMyNotes) },
        )
        RoleCard(
            title = "This is my new phone",
            body = "Show a code to the phone that already has your notes, then scan its reply.",
            enabled = true,
            disabledNote = null,
            onClick = { onChoose(PairingRole.NewDevice) },
        )
    }
}

@Composable
private fun RoleCard(
    title: String,
    body: String,
    enabled: Boolean,
    disabledNote: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(SurfaceDark)
            .then(
                if (enabled) {
                    Modifier.clickable(onClickLabel = title, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(18.dp),
    ) {
        val sw = LocalConfiguration.current.screenWidthDp
        Text(
            text = title,
            color = if (enabled) TitleGrey else Color(0xFF6E6E74),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = (sw * 0.045f).sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        CardBody(if (enabled) body else (disabledNote ?: body))
    }
}

/**
 * A step that puts a QR code on screen.
 *
 * The countdown is shown as a plain number of seconds rather than a progress ring: the code either
 * works or it does not, and two minutes is long enough that an animated ring would be decoration.
 */
@Composable
private fun ShowCodeStep(
    title: String,
    body: String,
    code: String,
    secondsRemaining: Int,
    sas: String?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onStartOver: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            CardTitle(title)
            CardBody(body)
        }
        QrCard(code)
        ExpiryLine(secondsRemaining)
        sas?.let { SasCard(it) }
        PrimaryButton(label = primaryLabel, onClick = onPrimary)
        StartOverButton(onStartOver)
    }
}

/**
 * The QR code itself, on the app's own dark card.
 *
 * Light modules on a dark ground rather than the printed convention, because a black app that
 * flashes a white sheet is jarring and, at night, blinding. `QrCodes.decodeLuminance` runs an
 * inverted second pass so Mañana reads its own codes, and most general scanners try inversion too.
 *
 * `FilterQuality.None` is what keeps the module edges hard when the tiny bitmap (one pixel per
 * module) is scaled up to card width. Any interpolation at all softens the edges the other phone's
 * camera has to resolve.
 */
@Composable
private fun QrCard(code: String) {
    val matrix = remember(code) { QrCodes.encode(code) }
    val image = rememberQrImageBitmap(
        matrix = matrix,
        dark = Color(0xFFEDEDED),
        light = SurfaceDark,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(SurfaceDark)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = BitmapPainter(image, filterQuality = FilterQuality.None),
            contentDescription = "Pairing code",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
    }
}

@Composable
private fun ExpiryLine(secondsRemaining: Int) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = "This code expires in ${secondsRemaining}s.",
        color = if (secondsRemaining <= 15) ErrorRed else Color(0xFF5E5E62),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = (sw * 0.033f).sp),
        modifier = Modifier.padding(start = 6.dp),
    )
}

/**
 * A step that puts the camera on screen, or explains why it cannot.
 *
 * The three permission states get three different screens on purpose: "ask", "ask again", and
 * "only Settings can fix this". An app that shows the same button to someone who chose "Don't
 * allow" twice looks broken rather than respectful.
 */
@Composable
private fun ScanStep(
    title: String,
    body: String,
    hint: ScanHint?,
    secondsRemaining: Int?,
    permission: CameraPermission,
    onCode: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartOver: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            CardTitle(title)
            CardBody(body)
        }

        when (permission) {
            CameraPermission.Granted -> ViewfinderCard(onCode)

            CameraPermission.Unknown -> Card {
                CardTitle("Camera")
                CardBody("Asking for permission to use the camera…")
            }

            CameraPermission.Denied -> Card {
                CardTitle("Camera access is off")
                CardBody(
                    "Reading the other phone's code needs the camera. It is the only " +
                        "permission this app asks for, and it is used on this screen only."
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(label = "Allow camera", onClick = onRequestPermission)
            }

            CameraPermission.PermanentlyDenied -> Card {
                CardTitle("Camera access is blocked")
                CardBody(
                    "Android will not ask again from here. Turn the camera on for Mañana " +
                        "in Settings, then come back to this screen."
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(label = "Open settings", onClick = onOpenSettings)
            }
        }

        hint?.let { HintLine(it) }
        secondsRemaining?.let { ExpiryLine(it) }
        StartOverButton(onStartOver)
    }
}

@Composable
private fun ViewfinderCard(onCode: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(CardRadius))
            .background(Color.Black),
    ) {
        my.cheysoff.feature_pairing.qr.QrScannerView(
            onCode = onCode,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun HintLine(hint: ScanHint) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = when (hint) {
            ScanHint.DifferentVersion ->
                "That code is from a different version of Mañana. Update both phones."
            ScanHint.WrongStep ->
                "That is the other step's code. Check both phones are on the right screen."
            ScanHint.OtherSession ->
                "That code belongs to a different pairing attempt. Start over on both phones."
        },
        color = ErrorRed,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = (sw * 0.033f).sp,
            lineHeight = (sw * 0.046f).sp,
        ),
        modifier = Modifier.padding(start = 6.dp),
    )
}

/** The six digits, big enough to read across a table. */
@Composable
private fun SasCard(sas: String) {
    val sw = LocalConfiguration.current.screenWidthDp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(SurfaceDark)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "CONFIRMATION CODE",
            color = Color(0xFF5E5E62),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = (sw * 0.030f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            // Spaced in two groups of three: six undifferentiated digits are hard to compare by
            // eye across two screens, which is the one job this string has.
            text = sas.chunked(3).joinToString("  "),
            color = TitleGrey,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = (sw * 0.105f).sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            ),
        )
    }
}

@Composable
private fun ConfirmStep(sas: String, onMatch: () -> Unit, onMismatch: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            CardTitle("Do both phones show this?")
            CardBody(
                "Both phones worked out these six digits independently. If they match, the two " +
                    "phones agreed on the same account key and you are paired. If they do not, " +
                    "something went to the wrong phone — say no and start over."
            )
        }
        SasCard(sas)
        PrimaryButton(label = "They match", onClick = onMatch)
        TextButton(onClick = onMismatch, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "They're different",
                color = ErrorRed,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = (LocalConfiguration.current.screenWidthDp * 0.038f).sp,
                ),
            )
        }
    }
}

@Composable
private fun FinishedCard(role: PairingRole, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            CardTitle("Paired")
            CardBody(
                when (role) {
                    PairingRole.NewDevice ->
                        "This phone now holds your account key. Nothing has been downloaded " +
                            "yet — syncing itself is not part of this build."

                    PairingRole.HasMyNotes ->
                        "The other phone now holds a copy of your account key. It is the only " +
                            "backup of it that exists: if you lose the PIN on this phone, that " +
                            "phone can still read your notes."
                }
            )
        }
        PrimaryButton(label = "Done", onClick = onDone)
    }
}

@Composable
private fun FailedCard(message: String, onStartOver: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CardRadius))
                .background(SurfaceDark)
                .padding(18.dp),
        ) {
            Column {
                Text(
                    text = "Pairing stopped",
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = (LocalConfiguration.current.screenWidthDp * 0.045f).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                CardBody(message)
            }
        }
        PrimaryButton(label = "Start over", onClick = onStartOver)
    }
}

/**
 * Open this app's page in system Settings.
 *
 * The only escape from a permanently denied permission: `requestPermissions` returns immediately
 * with a denial once the user has chosen "Don't allow" twice, and there is no API to re-prompt.
 */
private fun android.content.Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
