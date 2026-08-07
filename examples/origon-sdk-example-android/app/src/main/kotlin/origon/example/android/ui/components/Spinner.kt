package origon.example.android.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** iOS's `ProgressView().scaleEffect(0.8)` lands near 16pt; 20dp reads the same. */
private val SpinnerSize = 20.dp
private val SpinnerStroke = 2.dp

/**
 * The app's one busy indicator.
 *
 * The buttons and the composer alike get the same thing rather than two copies
 * of the size and stroke — the metrics are the only content, and a second copy
 * of a metric is how two spinners end up different sizes.
 */
@Composable
fun OrigonSpinner(color: Color, modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        color = color,
        strokeWidth = SpinnerStroke,
        modifier = modifier.size(SpinnerSize),
    )
}
