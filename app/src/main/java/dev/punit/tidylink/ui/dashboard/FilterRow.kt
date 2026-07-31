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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.punit.tidylink.R
import dev.punit.tidylink.data.local.TagCount

private const val MAX_VISIBLE_TAGS = 20

/**
 * Secondary filters as one scrolling row of chips, sitting under
 * [CategoryTiles]: the unread toggle first, then tags.
 *
 * Chips rather than tiles on purpose - these are finer-grained than
 * categories and there are many more of them. Both live in a single row so
 * the header doesn't grow a third band; the header scrolls away with the
 * grid, and every extra row is content pushed off the first screen.
 *
 * Every filter here composes with the category filter instead of replacing
 * it: a link has exactly one category but many tags, so "Dev + #kotlin +
 * unread" is a narrowing, not a contradiction.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FilterRow(
    tags: List<TagCount>,
    selected: String?,
    unreadOnly: Boolean,
    onSelect: (String?) -> Unit,
    onUnreadOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAllTags by rememberSaveable { mutableStateOf(false) }
    val topTags = tags.take(MAX_VISIBLE_TAGS)
    val hasOverflow = tags.size > topTags.size

    // No contentPadding: the caller owns the horizontal gutter (inside the
    // grid it comes from contentPadding), and two gutters double-indent.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        // First, and always present even in a library with no tags at all -
        // it's the filter that turns a growing pile into a queue.
        item {
            FilterChip(
                selected = unreadOnly,
                onClick = { onUnreadOnlyChange(!unreadOnly) },
                label = { Text(stringResource(R.string.chip_unread)) },
                leadingIcon = if (unreadOnly) {
                    { Icon(Icons.Default.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
        // Keep the active filter visible even when it isn't a top tag -
        // otherwise selecting a rare tag from the sheet hides the only
        // control that can clear it.
        if (selected != null && topTags.none { it.tag == selected }) {
            item {
                TagFilterChip(tag = selected, selected = true, onClick = { onSelect(null) })
            }
        }
        items(topTags, key = { it.tag }) { tag ->
            TagFilterChip(
                tag = tag.tag,
                selected = selected == tag.tag,
                onClick = { onSelect(if (selected == tag.tag) null else tag.tag) },
            )
        }
        if (hasOverflow) {
            item {
                FilterChip(
                    selected = false,
                    onClick = { showAllTags = true },
                    label = { Text(stringResource(R.string.tile_more)) },
                )
            }
        }
    }

    if (showAllTags) {
        ModalBottomSheet(onDismissRequest = { showAllTags = false }) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.sheet_filter_by_tag),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = selected == tag.tag,
                            onClick = {
                                onSelect(if (selected == tag.tag) null else tag.tag)
                                showAllTags = false
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.chip_tag_with_count,
                                        tag.tag,
                                        tag.count,
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

/** Named to not overload [TagChip], the non-interactive chip in LinkCard. */
@Composable
private fun TagFilterChip(tag: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            // "#" prefix inline rather than a string resource, matching
            // TagChip in LinkCard - it's punctuation, not translatable copy.
            Text(text = "#$tag", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}
