package dev.punit.tidylink.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.SortOrder

/** Bottom sheet listing the sort options, current one check-marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SortSheet(
    current: SortOrder,
    onSelect: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = glassSheetColor()) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.sheet_sort_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            SortOrder.entries.forEach { order ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(order) }
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = sortLabel(order),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (order == current) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (order == current) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.cd_selected),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** UI label for a sort order (the enum itself is a data-layer type). */
@Composable
private fun sortLabel(order: SortOrder): String = stringResource(
    when (order) {
        SortOrder.NEWEST -> R.string.sort_newest
        SortOrder.OLDEST -> R.string.sort_oldest
        SortOrder.TITLE_AZ -> R.string.sort_title_az
        SortOrder.TITLE_ZA -> R.string.sort_title_za
        SortOrder.CATEGORY -> R.string.sort_category
    }
)

/**
 * Material "sort" glyph, built by hand because material-icons-core doesn't
 * ship one (and the extended artifact isn't worth the dependency).
 */
internal val SortIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sort",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Three left-aligned bars of decreasing width.
            moveTo(3f, 18f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(-2f)
            horizontalLineTo(3f)
            close()
            moveTo(3f, 6f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(18f)
            verticalLineTo(6f)
            close()
            moveTo(3f, 13f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(-2f)
            horizontalLineTo(3f)
            close()
        }
    }.build()
}
