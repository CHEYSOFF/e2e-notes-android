package my.cheysoff.feature_notes.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.cheysoff.core_domain.model.EventData
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One event under the calendar grid: when it is, what it is called, and a way to remove it.
 *
 * An all-day event reads "All day" rather than a clock time. Its stored instant is UTC midnight by
 * construction, so a clock face derived from it would be wrong for anyone not on UTC -- see
 * `EventDrafts` for why that representation is the right one anyway.
 */
@Composable
internal fun EventRow(event: EventData, onDelete: () -> Unit) {
    var confirmDelete by remember(event.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(SurfaceDark, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 30.dp)
                .background(
                    color = event.colorArgb?.let { Color(it.toInt()) } ?: AccentIndigo,
                    shape = RoundedCornerShape(2.dp),
                ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = event.title.ifBlank { "Untitled event" },
                color = TitleGrey,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = eventTimeLabel(event),
                color = BodyGrey,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = { confirmDelete = true }) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = "Delete event",
                tint = BodyGrey,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            containerColor = SurfaceDark,
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this event?", color = TitleGrey) },
            // The same honesty the photo and drawing dialogs use: events are not kept in Trash.
            text = { Text("This cannot be undone.", color = BodyGrey) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("Delete", color = AccentIndigo) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = BodyGrey) }
            },
        )
    }
}

/**
 * "All day", or the start and end read straight off the stored local strings.
 *
 * Read rather than recomputed from the instants on purpose: the local strings are what the event
 * actually means, and re-deriving a time through the reader's zone is how an event drifts an hour
 * after a time-zone database update.
 */
internal fun eventTimeLabel(event: EventData): String {
    if (event.isAllDay) return "All day"
    val start = event.startLocal.substringAfter(LOCAL_SEPARATOR, "").take(5)
    val end = event.endLocal.substringAfter(LOCAL_SEPARATOR, "").take(5)
    return if (start.isEmpty()) "" else "$start - $end"
}

private const val LOCAL_SEPARATOR = "T"

/**
 * Title, a start time, and a length. Deliberately small.
 *
 * There is no repeat control and no reminder control, because this build cannot express either --
 * their columns are reserved and always empty, and `EventsRepository.saveEvent` refuses an event
 * carrying them. Offering the controls would promise something the record cannot carry.
 */
@Composable
internal fun EventComposer(
    day: LocalDate,
    onDismiss: () -> Unit,
    onSave: (title: String, startMinutes: Int?, durationMinutes: Int) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var startMinutes by remember { mutableIntStateOf(DEFAULT_START_MINUTES) }
    var durationMinutes by remember { mutableIntStateOf(DEFAULT_DURATION_MINUTES) }

    AlertDialog(
        containerColor = SurfaceDark,
        onDismissRequest = onDismiss,
        title = { Text("New event", color = TitleGrey) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = composerDayLabel(day),
                    color = BodyGrey,
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    placeholder = { Text("What is it?", color = PlaceholderGrey) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TitleGrey,
                        unfocusedTextColor = TitleGrey,
                        focusedBorderColor = AccentIndigo,
                        unfocusedBorderColor = OutlineGrey,
                        cursorColor = AccentIndigo,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = allDay,
                        onCheckedChange = { allDay = it },
                        colors = CheckboxDefaults.colors(checkedColor = AccentIndigo),
                    )
                    Text("All day", color = TitleGrey, style = MaterialTheme.typography.bodyMedium)
                }
                if (!allDay) {
                    StepperRow(
                        label = "Starts",
                        value = minutesLabel(startMinutes),
                        onLess = { startMinutes = (startMinutes - STEP_MINUTES).coerceAtLeast(0) },
                        onMore = { startMinutes = (startMinutes + STEP_MINUTES).coerceAtMost(LAST_START_MINUTES) },
                    )
                    StepperRow(
                        label = "Lasts",
                        value = durationLabel(durationMinutes),
                        onLess = { durationMinutes = (durationMinutes - STEP_MINUTES).coerceAtLeast(STEP_MINUTES) },
                        onMore = { durationMinutes = (durationMinutes + STEP_MINUTES).coerceAtMost(MAX_DURATION_MINUTES) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onSave(title, if (allDay) null else startMinutes, durationMinutes) },
            ) {
                Text("Save", color = if (title.isNotBlank()) AccentIndigo else BodyGrey)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = BodyGrey) } },
    )
}

@Composable
private fun StepperRow(label: String, value: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = BodyGrey,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onLess) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Earlier $label", tint = TitleGrey)
        }
        Text(value, color = TitleGrey, style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onMore) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Later $label", tint = TitleGrey)
        }
    }
}

/** "09:30", from minutes past midnight. */
internal fun minutesLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return buildString {
        if (hours < 10) append('0')
        append(hours)
        append(':')
        if (rest < 10) append('0')
        append(rest)
    }
}

/** "1h 30m", or "45m" under the hour. */
internal fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> rest.toString() + "m"
        rest == 0 -> hours.toString() + "h"
        else -> hours.toString() + "h " + rest.toString() + "m"
    }
}

/** "8 September", with the year only when it is not the current one. */
private fun composerDayLabel(day: LocalDate): String {
    val pattern = if (day.year == LocalDate.now().year) "d MMMM" else "d MMMM yyyy"
    return day.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

private val PlaceholderGrey = Color(0xFF4A4A50)
private val OutlineGrey = Color(0xFF2E2E34)

private const val DEFAULT_START_MINUTES = 9 * 60
private const val DEFAULT_DURATION_MINUTES = 60
private const val STEP_MINUTES = 30
private const val LAST_START_MINUTES = 23 * 60 + 30
private const val MAX_DURATION_MINUTES = 12 * 60
