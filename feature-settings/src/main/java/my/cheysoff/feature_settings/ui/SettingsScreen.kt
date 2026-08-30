package my.cheysoff.feature_settings.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_ui.model.menuLabel
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.IndigoTint
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.OutlineDark
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.feature_settings.model.SettingsIntent
import my.cheysoff.feature_settings.model.SettingsScreenState
import my.cheysoff.feature_settings.model.biometricRowInteractive
import my.cheysoff.feature_settings.model.biometricRowSubtitle

/** Corner radius of a settings card. Between Radii.medium and Radii.large, matching a note card. */
private val CardRadius = 20.dp

/**
 * Start padding Material3 puts around a [TopAppBar]'s navigation-icon slot before any padding of
 * ours is applied — the library's own `TopAppBarHorizontalPadding`, which it keeps private and
 * does not expose. It is subtracted in [SettingsTopBar] so the arrow lands at the same x as the
 * back arrows in the editor's and Trash's hand-rolled bars, which have no such inset to undo.
 */
private val TopAppBarNavIconInset = 4.dp

/**
 * The Profile tab: everything the app lets you change, plus an honest account of what it does with
 * your notes.
 *
 * A pushed destination rather than a tab you stay on — the floating nav bar's four icons are the
 * notes list's own, and only the list is a place to be. [onBack] returns there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsScreenState,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sw = LocalConfiguration.current.screenWidthDp
    // BiometricPrompt must be hosted by a FragmentActivity, and MainActivity is one precisely for
    // that reason (see the comment on its declaration). Resolved the same way AuthScreen resolves
    // it for the unlock prompt.
    val activity = LocalContext.current as? FragmentActivity

    // Pinned rather than collapsing or enter-always: the bar keeps one height and never moves, so
    // the back arrow — the only way off this screen — is on screen at every scroll position.
    // The connection handed to the Scaffold below is what keeps this behavior's
    // TopAppBarState.contentOffset up to date as the content scrolls. That offset drives exactly
    // one thing: the crossfade between the bar's containerColor and its scrolledContainerColor,
    // which SettingsTopBar deliberately sets to the same black. So the bar looks identical
    // scrolled and unscrolled, which is what a flat black app wants.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SettingsTopBar(onBack = onBack, scrollBehavior = scrollBehavior) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Both paddings sit INSIDE the scroll, so the top one is a spacer that scrolls
                // away with the content rather than a fixed gap: the display header below slides
                // up underneath the bar instead of stopping at its lower edge. The Scaffold lays
                // this Column out full-screen and draws the bar over it, so that only reads
                // correctly because the bar is opaque — see SettingsTopBar's colors.
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                )
                .padding(horizontal = spacing.screenHorizontal),
        ) {
            // Same two-tone construction as the notes list's header line: a Light first word in
            // TitleGrey over a Medium accent word in IndigoTint.
            val headerSize = (sw * 0.092f).sp
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = TitleGrey, fontWeight = FontWeight.Light)) {
                        append("Your")
                    }
                    append("\n")
                    withStyle(SpanStyle(color = IndigoTint, fontWeight = FontWeight.Medium)) {
                        append("settings.")
                    }
                },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = headerSize,
                    lineHeight = headerSize * 1.05f,
                    letterSpacing = (-0.6).sp,
                ),
                modifier = Modifier.padding(start = 4.dp, top = spacing.s),
            )

            SectionLabel("Header")
            SettingsCard {
                ToggleRow(
                    title = "Greetings",
                    subtitle = "Time-of-day greetings in the notes-list header.",
                    checked = state.showGreetings,
                    onCheckedChange = { onIntent(SettingsIntent.SetShowGreetings(it)) },
                )
                RowDivider()
                ToggleRow(
                    title = "Daily phrases",
                    subtitle = "Short prompts like \"One thing at a time.\"",
                    checked = state.showDailyPhrases,
                    onCheckedChange = { onIntent(SettingsIntent.SetShowDailyPhrases(it)) },
                )
                RowDivider()
                ToggleRow(
                    title = "Stats line",
                    subtitle = "How many notes you have, and how many are pinned.",
                    checked = state.showStats,
                    onCheckedChange = { onIntent(SettingsIntent.SetShowStats(it)) },
                )
            }
            // Checked against NotesListViewModel.pickMotivationalLine: with both sources off it
            // returns null, and NotesListScreen's HeaderLine draws the small "Mañana" wordmark
            // for a null header. The stats line is a separate sub-line, unaffected by that.
            FootNote("With greetings and phrases both off, the header is just the Mañana wordmark.")

            SectionLabel("Notes")
            SettingsCard {
                SortRow(
                    order = state.sortOrder,
                    onSelect = { onIntent(SettingsIntent.SortOrderSelected(it)) },
                )
            }
            // Said plainly rather than calling this a "default": there is one stored order and
            // both surfaces read and write it, so changing it here moves the list's pill too.
            FootNote("The same setting as the sort control on the notes list.")

            SectionLabel("Security")
            SettingsCard {
                ToggleRow(
                    title = "Biometric unlock",
                    subtitle = biometricRowSubtitle(state.biometricStatus, state.biometricEnabled),
                    checked = state.biometricEnabled,
                    // Turning it OFF never needs the prompt, but the intent carries the activity
                    // either way, so without a FragmentActivity host there is nothing this row
                    // can do and it stays inert rather than silently swallowing taps.
                    enabled = activity != null &&
                        biometricRowInteractive(state.biometricStatus, state.biometricEnabled),
                    busy = state.biometricBusy,
                    onCheckedChange = { wanted ->
                        activity?.let { onIntent(SettingsIntent.SetBiometricEnabled(wanted, it)) }
                    },
                )
            }
            state.biometricNotice?.let { FootNote(it) }

            SectionLabel("About")
            AboutCard(version = state.appVersion)

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

// ── Building blocks ─────────────────────────────────────────────────────────

/**
 * The pinned bar: a back arrow and nothing else.
 *
 * The back arrow is the only way out — this screen has no nav bar of its own, because the nav bar
 * belongs to the notes list — and it used to be the first item of the scrolling content, so it
 * left the screen as soon as you read past the first card. It is a bar now.
 *
 * The two-tone "Your settings." display header stays where it was, as the first thing in the
 * scrolling content, and slides up under this bar. That is deliberately NOT a LargeTopAppBar:
 * that header is two lines of the app's own display type, a large bar collapses its title into a
 * single-line Material one, and no screen in this app has a Material bar title at all — the notes
 * list has no top bar, and Trash's is a bare back arrow with the same two-line header below it in
 * the content. Keeping the header in the content is what makes this screen read as the same app.
 *
 * The colors are spelled out because Material's defaults are not this app's: it gives a bar one
 * surface color unscrolled and a lighter, elevation-tinted one once content is underneath it, so
 * an unstyled bar would go grey the moment you scrolled — a tint that appears nowhere else in a
 * flat pure-black design. Both are pinned here to the same colorScheme.background the Scaffold
 * uses (AppBlack: MainActivity passes `darkTheme = true`, so the dark scheme is the only one this
 * app renders), which also makes the bar opaque enough to hide the content scrolling beneath it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    val spacing = LocalSpacing.current
    TopAppBar(
        title = {},
        navigationIcon = {
            // Why the arrow was off before, and where this number comes from. An IconButton is a
            // 48.dp touch target with a 24.dp icon centred in it, so it draws its icon 12.dp
            // inside its own box. The old header put that IconButton in the content Column, whose
            // start padding is the plain screenHorizontal (16.dp) every card below it sits on —
            // so the touch target was flush with the cards but the icon it shows was not: the
            // 24.dp icon began at 16 + 12 = 28.dp, a whole 12.dp inboard of the cards.
            //
            // EditorTopBar and TrashTopBar already answer this: they pad by
            // `screenHorizontal - 4.dp`, pulling the touch target 4.dp back out to trade against
            // that inset. Material3 then adds [TopAppBarNavIconInset] of its own around this slot,
            // which those hand-rolled Rows do not have, so it is subtracted too. The 48.dp target
            // is untouched — this is padding around the IconButton, not inside it.
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(
                    start = spacing.screenHorizontal - 4.dp - TopAppBarNavIconInset,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to notes",
                    tint = BodyGrey,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            navigationIconContentColor = BodyGrey,
            titleContentColor = TitleGrey,
            actionIconContentColor = BodyGrey,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/** Uppercase section heading, matching the notes list's "PINNED" / "RECENT" labels. */
@Composable
private fun SectionLabel(text: String) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = text.uppercase(),
        color = Color(0xFF5E5E62),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = (sw * 0.032f).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        ),
        modifier = Modifier.padding(start = 4.dp, top = 26.dp, bottom = 10.dp),
    )
}

/** A grouped card on the dark surface — the same ground and radius family as a note card. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardRadius))
            .background(SurfaceDark),
    ) {
        content()
    }
}

/** Hairline between rows inside a card, inset so it doesn't touch the rounded corners. */
@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .padding(start = 18.dp, end = 18.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(OutlineDark),
    )
}

/** Small explanatory line under a card. Never a control — purely commentary. */
@Composable
private fun FootNote(text: String) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = text,
        color = Color(0xFF5E5E62),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = (sw * 0.033f).sp,
            lineHeight = (sw * 0.046f).sp,
        ),
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
    )
}

/**
 * Title + explanation on the left, a switch on the right.
 *
 * The whole row is NOT clickable: rows here differ in whether they can be operated at all
 * ([enabled]) and one of them starts a system prompt, so the switch stays the single, obvious
 * target rather than a large invisible one.
 */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val sw = LocalConfiguration.current.screenWidthDp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled || busy) TitleGrey else Color(0xFF6E6E74),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = (sw * 0.043f).sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = BodyGrey,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = (sw * 0.034f).sp,
                    lineHeight = (sw * 0.046f).sp,
                ),
            )
        }
        Spacer(Modifier.width(12.dp))
        if (busy) {
            // Occupies the switch's slot while a system prompt is up or a write is in flight, so
            // the row doesn't reflow and the control can't be driven twice.
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = IndigoTint,
                strokeWidth = 2.dp,
            )
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = mananaSwitchColors(),
            )
        }
    }
}

/** Switch palette: indigo when on, near-black when off — the app's own accent, not Material's. */
@Composable
private fun mananaSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color(0xFFE8E6F5),
    checkedTrackColor = AccentIndigo,
    checkedBorderColor = AccentIndigo,
    uncheckedThumbColor = Color(0xFF6A6A70),
    uncheckedTrackColor = Color(0xFF1D1D22),
    uncheckedBorderColor = OutlineDark,
    disabledCheckedThumbColor = Color(0xFF8A87A0),
    disabledCheckedTrackColor = Color(0xFF241C56),
    disabledCheckedBorderColor = Color(0xFF241C56),
    disabledUncheckedThumbColor = Color(0xFF44444A),
    disabledUncheckedTrackColor = Color(0xFF151519),
    disabledUncheckedBorderColor = Color(0xFF26262B),
)

/**
 * The notes-order row: a label plus a pill that opens the same three orders the notes list's sort
 * pill offers, styled the same way (SurfaceDark ground, indigo check on the active row) so the two
 * are recognisably the same control.
 */
@Composable
private fun SortRow(order: NotesSortOrder, onSelect: (NotesSortOrder) -> Unit) {
    val sw = LocalConfiguration.current.screenWidthDp
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Notes order",
            color = TitleGrey,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = (sw * 0.043f).sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier.weight(1f),
        )
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF1D1D22))
                    // The whole Row is the tap target, so the accessible name and the button role
                    // belong here rather than on the Icon inside it.
                    .clickable(onClickLabel = "Change notes order", role = Role.Button) {
                        menuOpen = true
                    }
                    .padding(start = 11.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.SwapVert,
                    contentDescription = null,
                    tint = IndigoTint,
                    modifier = Modifier.size((sw * 0.045f).dp),
                )
                Text(
                    text = order.menuLabel,
                    color = Color(0xFF8A8A8A),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = (sw * 0.036f).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = SurfaceDark,
                shape = RoundedCornerShape(14.dp),
            ) {
                NotesSortOrder.entries.forEach { candidate ->
                    val active = candidate == order
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = candidate.menuLabel,
                                color = if (active) IndigoTint else TitleGrey,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                ),
                            )
                        },
                        // Every row reserves the trailing slot (a blank spacer when it is not the
                        // active one) so all three labels stay left-aligned at the same x.
                        trailingIcon = {
                            if (active) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = IndigoTint,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Spacer(Modifier.size(18.dp))
                            }
                        },
                        onClick = { menuOpen = false; onSelect(candidate) },
                    )
                }
            }
        }
    }
}

/**
 * What the app actually does with your notes.
 *
 * Every claim below is checkable against the code it describes, and is deliberately no stronger
 * than that code supports:
 *  - SQLCipher + the passphrase: DataModule.provideNoteDatabase.
 *  - PIN wrap, PBKDF2-HMAC-SHA256 at 210,000 iterations, AES-256-GCM: PassphraseCipher.
 *  - biometric wrap in an AES-256-GCM Keystore key requiring authentication, invalidated by
 *    re-enrollment: BiometricKeystoreCipher.
 *  - the wrong-PIN backoff numbers: LockoutPolicy (5 free attempts, 30s base, x2, 5min cap).
 *  - re-lock on background: MainApplication's ProcessLifecycleOwner observer.
 *  - no networking: the project contains no HTTP client and no network code, and the merged debug
 *    manifest declares only USE_BIOMETRIC, USE_FINGERPRINT and Compose's own
 *    DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION — no INTERNET.
 *
 * The key is described as "random" rather than as a specific length because an install migrated
 * from the pre-PIN key manager reuses whatever passphrase that version generated; only fresh
 * installs are guaranteed to be the 32 bytes SecureUnlockManager creates today.
 */
@Composable
private fun AboutCard(version: String) {
    val sw = LocalConfiguration.current.screenWidthDp
    SettingsCard {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "Mañana",
                    color = TitleGrey,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = (sw * 0.052f).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = version,
                    color = BodyGrey,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (sw * 0.034f).sp,
                    ),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            AboutParagraph(
                "Your notes are stored in a SQLCipher-encrypted database on this device. " +
                    "There is no account and no server: nothing is uploaded, and there is " +
                    "nothing to sign in to."
            )
            AboutParagraph(
                "The database key is random and is never stored in the clear. It is encrypted " +
                    "with AES-256-GCM under a key derived from your PIN using PBKDF2-HMAC-SHA256 " +
                    "at 210,000 iterations. The PIN itself is never stored."
            )
            AboutParagraph(
                "With biometric unlock on, a second copy of that key is encrypted under an " +
                    "AES-256-GCM key in the Android Keystore that can only be used after a " +
                    "biometric match. Re-enrolling your biometrics invalidates that key; your " +
                    "PIN keeps working."
            )
            AboutParagraph(
                "The first five wrong PIN entries are free. The sixth locks the app for 30 " +
                    "seconds, and each further wrong entry doubles the wait, up to five minutes."
            )
            AboutParagraph(
                "Unlocking keeps the key in memory so your notes can be read. Sending the app " +
                    "to the background locks it again."
            )
            // The one claim that matters most, and the one users are most often lied to about.
            AboutParagraph(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = TitleGrey, fontWeight = FontWeight.Medium)) {
                        append("There is no recovery. ")
                    }
                    append(
                        "No reset link, no backup key, nobody to ask. If you forget your PIN " +
                            "and biometric unlock is off or no longer works, the notes stay " +
                            "encrypted and cannot be read again."
                    )
                }
            )
        }
    }
}

@Composable
private fun AboutParagraph(text: String) {
    AboutParagraph(buildAnnotatedString { append(text) })
}

@Composable
private fun AboutParagraph(text: androidx.compose.ui.text.AnnotatedString) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = text,
        color = BodyGrey,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = (sw * 0.035f).sp,
            lineHeight = (sw * 0.05f).sp,
        ),
        modifier = Modifier.padding(bottom = 12.dp),
    )
}
