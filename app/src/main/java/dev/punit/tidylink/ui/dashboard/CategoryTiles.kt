package dev.punit.tidylink.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.CategoryCount
import dev.punit.tidylink.data.repository.CategoryNames

private const val MAX_VISIBLE_CATEGORY_TILES = 8

/**
 * Category filter as a row of icon + label tiles (grocery-app style): the
 * selected tile gets a raised tonal card look. Only the busiest categories
 * get a tile; a "More" tile opens a bottom sheet with the full list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CategoryTiles(
    categories: List<CategoryCount>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAllCategories by rememberSaveable { mutableStateOf(false) }
    val topCategories = categories.take(MAX_VISIBLE_CATEGORY_TILES)
    val hasOverflow = categories.size > topCategories.size

    // No contentPadding: the caller owns the horizontal gutter (inside the
    // grid it comes from contentPadding), and two gutters double-indent.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        item {
            CategoryTile(
                label = stringResource(R.string.chip_all),
                icon = Icons.AutoMirrored.Filled.List,
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        // Keep the active filter visible even when it isn't a top category.
        if (selected != null && topCategories.none { it.category == selected }) {
            item {
                CategoryTile(
                    label = selected,
                    icon = categoryIcon(selected),
                    selected = true,
                    onClick = { onSelect(null) },
                )
            }
        }
        items(topCategories, key = { it.category }) { cat ->
            CategoryTile(
                label = cat.category,
                icon = categoryIcon(cat.category),
                selected = selected == cat.category,
                onClick = { onSelect(if (selected == cat.category) null else cat.category) },
            )
        }
        if (hasOverflow) {
            item {
                CategoryTile(
                    label = stringResource(R.string.tile_more),
                    icon = Icons.Default.MoreVert,
                    selected = false,
                    onClick = { showAllCategories = true },
                )
            }
        }
    }

    if (showAllCategories) {
        ModalBottomSheet(onDismissRequest = { showAllCategories = false }) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.sheet_filter_by_category),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selected == cat.category,
                            onClick = {
                                onSelect(if (selected == cat.category) null else cat.category)
                                showAllCategories = false
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.chip_category_with_count,
                                        cat.category,
                                        cat.count,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .widthIn(min = 64.dp, max = 104.dp)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Icon for an AI-generated category name. Matching goes through
 * [CategoryNames.key] so "Social Media", "social-media" and "socials" all
 * hit the same entry; anything unknown falls back to a folder. Core
 * material icons only - the extended artifact stays out (SortIcon
 * precedent), so some categories share an icon on purpose.
 */
private val categoryIcons: Map<String, ImageVector> by lazy {
    listOf(
        "Technology" to Icons.Default.Build,
        "Tech" to Icons.Default.Build,
        "AI" to Icons.Default.Build,
        "Tools" to Icons.Default.Build,
        "Programming" to Icons.Default.Build,
        "News" to Icons.Default.Info,
        "Videos" to Icons.Default.PlayArrow,
        "Music" to Icons.Default.PlayArrow,
        "Entertainment" to Icons.Default.Star,
        "Shopping" to Icons.Default.ShoppingCart,
        "Finance" to Icons.Default.ShoppingCart,
        "Social Media" to Icons.Default.Person,
        "Social" to Icons.Default.Person,
        "Travel" to Icons.Default.Place,
        "Food" to Icons.Default.Favorite,
        "Recipes" to Icons.Default.Favorite,
        "Cooking" to Icons.Default.Favorite,
        "Health" to Icons.Default.FavoriteBorder,
        "Fitness" to Icons.Default.FavoriteBorder,
        "Education" to Icons.Default.Create,
        "Learning" to Icons.Default.Create,
        "Articles" to Icons.Default.Create,
        "Blogs" to Icons.Default.Create,
        "Writing" to Icons.Default.Create,
        "Career" to Icons.Default.AccountBox,
        "Jobs" to Icons.Default.AccountBox,
        "Home" to Icons.Default.Home,
        "Email" to Icons.Default.Email,
    ).associate { (name, icon) -> CategoryNames.key(name) to icon }
}

internal fun categoryIcon(name: String): ImageVector =
    categoryIcons[CategoryNames.key(name)] ?: FolderIcon

/**
 * Material "folder" glyph, built by hand because material-icons-core
 * doesn't ship one (same reasoning as SortIcon in DashboardScreen).
 */
private val FolderIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Folder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10f, 4f)
            horizontalLineTo(4f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(12f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(16f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(8f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            horizontalLineToRelative(-8f)
            lineToRelative(-2f, -2f)
            close()
        }
    }.build()
}
