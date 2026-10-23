package dev.r2d8.weather.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.r2d8.weather.R
import dev.r2d8.weather.data.PlaceSearchResult
import dev.r2d8.weather.domain.SavedPlace

/** Search for a city to add, and manage the saved list. [onDone] returns to the weather screen. */
@Composable
fun PlacesRoute(onBack: () -> Unit, onDone: () -> Unit, viewModel: PlacesViewModel = viewModel(factory = PlacesViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlacesScreen(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onAdd = { viewModel.add(it, onDone) },
        onSelect = { viewModel.select(it, onDone) },
        onRemove = viewModel::remove,
        onMove = viewModel::move,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    state: PlacesUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onAdd: (PlaceSearchResult) -> Unit,
    onSelect: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Places") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("Search for a city") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = if (state.query.isNotEmpty()) ({
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear")
                    }
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            if (state.query.isBlank()) {
                SavedList(state, onSelect, onRemove, onMove)
            } else {
                SearchResults(state.search, onAdd)
            }
        }
    }
}

@Composable
private fun SearchResults(search: SearchState, onAdd: (PlaceSearchResult) -> Unit) {
    when (search) {
        SearchState.Idle -> Hint("Keep typing…")
        SearchState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator(Modifier.padding(32.dp))
        }
        SearchState.Failed -> Hint("Couldn't search. Check your internet connection.")
        is SearchState.Results -> if (search.items.isEmpty()) {
            Hint("No cities found.")
        } else {
            LazyColumn {
                itemsIndexed(search.items) { index, item ->
                    if (index > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(item.place.name) },
                        supportingContent = item.region?.let { { Text(it) } },
                        leadingContent = { Icon(painterResource(R.drawable.ic_location_on), contentDescription = null) },
                        modifier = Modifier.clickable { onAdd(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedList(state: PlacesUiState, onSelect: (Long) -> Unit, onRemove: (Long) -> Unit, onMove: (Long, Int) -> Unit) {
    if (state.places.isEmpty()) {
        Hint("No saved places yet. Search for a city above to add it. \"Current location\" is always available from the drop-down on the main screen.")
        return
    }
    LazyColumn {
        itemsIndexed(state.places, key = { _, place -> place.id }) { index, saved ->
            if (index > 0) HorizontalDivider()
            SavedRow(
                saved = saved,
                isSelected = saved.id == state.selectedId,
                canMoveUp = index > 0,
                canMoveDown = index < state.places.lastIndex,
                onSelect = { onSelect(saved.id) },
                onRemove = { onRemove(saved.id) },
                onMove = { onMove(saved.id, it) },
            )
        }
    }
}

@Composable
private fun SavedRow(
    saved: SavedPlace,
    isSelected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    ListItem(
        headlineContent = { Text(saved.place.name) },
        supportingContent = saved.region?.let { { Text(it) } },
        leadingContent = {
            Icon(
                painterResource(R.drawable.ic_location_on),
                contentDescription = if (isSelected) "Shown on the main screen" else null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = "Move down")
                }
                IconButton(onClick = onRemove) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete ${saved.place.name}")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
