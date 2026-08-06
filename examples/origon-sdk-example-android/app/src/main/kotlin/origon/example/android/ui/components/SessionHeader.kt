package origon.example.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import origon.example.android.R
import origon.example.android.ui.theme.OrigonTheme

/**
 * The chat surface's header: session history on the left, new-session on the
 * right. What iOS spells as a `.toolbar` on `ChatView`.
 *
 * **The left button is not optional.** The system owns the left-edge swipe
 * gesture, so the edge swipe cannot be the only way to reach the drawer — a
 * screen without this button strands the user with no way back to the session
 * list.
 */
@Composable
fun SessionHeader(
    onMenuTap: () -> Unit,
    modifier: Modifier = Modifier,
    showPlus: Boolean = false,
    onNewSession: () -> Unit = {},
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 8.dp),
    ) {
        HeaderButton(
            painterId = R.drawable.ic_history,
            label = "Session history",
            iconSize = 24.dp,
            onClick = onMenuTap,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        if (showPlus) {
            HeaderButton(
                painterId = R.drawable.ic_plus,
                label = "New session",
                iconSize = 22.dp,
                onClick = onNewSession,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun HeaderButton(
    painterId: Int,
    label: String,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
    ) {
        Icon(
            painterResource(painterId),
            contentDescription = label,
            tint = OrigonTheme.colors.textPrimary,
            modifier = Modifier.size(iconSize),
        )
    }
}
