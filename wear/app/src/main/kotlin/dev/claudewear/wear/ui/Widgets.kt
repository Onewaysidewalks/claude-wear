package dev.claudewear.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeSource
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults

/**
 * Text entry, such as it is on a watch: the platform keyboard, handwriting, or whatever
 * input method the wearer has.
 *
 * M3 replaces this everywhere with the platform recognizer — dictation in-app and
 * `RemoteInput` from the shade — which is the input this product is actually for. Until
 * then a field is honest about being the fallback rather than pretending to be the path.
 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    numeric: Boolean = false,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Uri,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2B2B2B))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        if (value.isEmpty() && hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.body2,
                color = Color(0xFF8A8A8A),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 12.dp),
            )
        }
    }
}

/**
 * The clock a screen draws, or null for the real one.
 *
 * Nothing in the app ever provides this; [Gallery] does. A screenshot whose top edge is the
 * current time is a screenshot that stops matching a minute after it was taken, and there is
 * no way to hold Robolectric's wall clock still — it is the host's.
 */
internal val LocalTimeSource = staticCompositionLocalOf<TimeSource?> { null }

/**
 * The chrome every screen wears: the time across the top of the bezel, the scroll position
 * down the side of it.
 */
@Composable
fun WatchScaffold(listState: ScalingLazyListState, content: @Composable () -> Unit) {
    val time = LocalTimeSource.current ?: TimeTextDefaults.timeSource(TimeTextDefaults.timeFormat())
    Scaffold(
        timeText = { TimeText(timeSource = time) },
        positionIndicator = { PositionIndicator(listState) },
        content = content,
    )
}

/** A header line, for the top of a ScalingLazyColumn. */
@Composable
fun ScreenTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.title3,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
fun Caption(text: String, colour: Color = MaterialTheme.colors.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.caption2, color = colour)
}

@Composable
fun Problem(text: String) {
    Text(text, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.error)
}

@Composable
fun Spinner() {
    CircularProgressIndicator(
        strokeWidth = 2.dp,
        indicatorColor = MaterialTheme.colors.primary,
        modifier = Modifier.size(20.dp),
    )
}
