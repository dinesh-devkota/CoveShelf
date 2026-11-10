package com.dineshdevkota.cinevault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Single-choice fields apply immediately. For a multiple-choice field, [allLabel] names the
 * unrestricted selection and changes remain a draft until Apply. System Back dismisses either.
 */
@Composable
internal fun <T : Any> LibraryFilterMenu(
    label: String,
    values: Set<T>,
    options: List<T>,
    text: (T) -> String,
    onApply: (Set<T>) -> Unit,
    modifier: Modifier = Modifier,
    allLabel: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val summary = when (values.size) {
        0 -> allLabel.orEmpty()
        1 -> text(values.first())
        else -> "${values.size} selected"
    }
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Text("$label: $summary", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val tint = LocalContentColor.current
        Canvas(Modifier.size(12.dp)) {
            val chevron = Path().apply {
                moveTo(size.width * 0.1f, size.height * 0.35f)
                lineTo(size.width * 0.9f, size.height * 0.35f)
                lineTo(size.width * 0.5f, size.height * 0.75f)
                close()
            }
            drawPath(chevron, tint)
        }
    }
    if (!open) return

    Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var draft by remember { mutableStateOf(values) }
        val choices: List<T?> = remember(options, allLabel) { if (allLabel == null) options else listOf(null) + options }
        val start = remember { choices.indexOfFirst { it in values }.coerceAtLeast(0) }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
        val focus = remember { FocusRequester() }
        val applyFocus = remember { FocusRequester() }
        var placed by remember { mutableStateOf(false) }
        var initiallyFocused by remember { mutableStateOf(false) }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(placed, windowFocused) {
            if (placed && windowFocused && !initiallyFocused) initiallyFocused = focus.requestFocus()
        }
        Column(
            Modifier.width(if (allLabel == null) 360.dp else 480.dp).heightIn(max = 440.dp)
                .background(SurfaceColor, RoundedCornerShape(16.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            Row(modifier = Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).focusRestorer().focusGroup(), state = list,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(choices.size) { index ->
                        val choice = choices[index]
                        val checked = if (choice == null) draft.isEmpty() else choice in draft
                        val initial = if (index == start) Modifier.focusRequester(focus).onGloballyPositioned { placed = true } else Modifier
                        OutlinedButton(
                            modifier = initial.fillMaxWidth().focusProperties {
                                if (allLabel != null) right = applyFocus
                            }.semantics {
                                if (allLabel == null) {
                                    role = Role.RadioButton
                                    selected = checked
                                } else {
                                    role = Role.Checkbox
                                    toggleableState = ToggleableState(checked)
                                }
                            },
                            onClick = {
                                when {
                                    choice == null -> draft = emptySet()
                                    allLabel == null -> {
                                        onApply(setOf(choice))
                                        open = false
                                    }
                                    choice in draft -> draft -= choice
                                    else -> draft += choice
                                }
                            },
                        ) {
                            if (allLabel == null) RadioButton(selected = checked, onClick = null)
                            else Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(choice?.let(text) ?: allLabel.orEmpty(), modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (allLabel != null) Column(Modifier.padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(modifier = Modifier.focusRequester(applyFocus), onClick = {
                        onApply(draft.intersect(options.toSet()))
                        open = false
                    }) { Text("Apply") }
                    OutlinedButton(onClick = { open = false }) { Text("Cancel") }
                }
            }
        }
    }
}
