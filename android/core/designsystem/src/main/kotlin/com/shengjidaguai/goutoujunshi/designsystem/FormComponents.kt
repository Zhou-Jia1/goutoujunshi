@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shengjidaguai.goutoujunshi.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString

/** Caller owns RAM-only state. Secure input disables copy/cut and never reveals typed characters. */
@Composable fun SensitiveTextField(state: TextFieldState, label: String) {
    val focus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.s)) {
        BodyText(label)
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            BasicSecureTextField(state = state, textObfuscationMode = TextObfuscationMode.Hidden,
                modifier = Modifier.fillMaxWidth().padding(JunshiTheme.spacing.m).focusRequester(focus).onFocusChanged { hasFocus = it.isFocused }
                    .clearAndSetSemantics {
                        // BasicSecureTextField masks pixels but exposes its original input through
                        // semantics/autofill. Export only bullets, with explicit accessible edit actions.
                        contentDescription = label
                        password()
                        val masked = AnnotatedString("•".repeat(state.text.length))
                        editableText = masked
                        this[SemanticsProperties.InputText] = masked
                        isEditable = true
                        focused = hasFocus
                        textSelectionRange = state.selection
                        requestFocus { focus.requestFocus(); true }
                        onClick { focus.requestFocus(); true }
                        setText { value -> state.edit { replace(0, length, value.text) }; true }
                        insertTextAtCursor { value -> state.edit { replace(selection.min, selection.max, value.text) }; true }
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface))
        }
    }
}

@Composable fun BodyText(text: String, modifier: Modifier = Modifier, secondary: Boolean = false) {
    Text(text, modifier, style = if (secondary) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
}

@Composable fun FormTextField(value: String, onValueChange: (String) -> Unit, label: String, secret: Boolean = false) {
    OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}

@Composable fun ChoiceField(label: String, value: String, options: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SecondaryButton("$label：$value", { expanded = true })
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (name, action) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; action() }) }
        }
    }
}

@Composable fun ContentDialog(title: String, closeLabel: String, onClose: () -> Unit,
    confirm: Pair<String, () -> Unit>? = null, content: @Composable ColumnScope.() -> Unit) {
    // A dialog creates a new window which resets LocalDensity. Reapply the caller's effective
    // font scale inside every slot so system × app scaling also reaches title and actions.
    val effectiveDensity = LocalDensity.current
    BasicAlertDialog(onDismissRequest = onClose) {
        CompositionLocalProvider(LocalDensity provides effectiveDensity) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
                    .padding(JunshiTheme.spacing.xl), verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(JunshiTheme.spacing.m), content = content)
                    // One shared layout measures both actions at the same effective density.
                    // Stacking keeps each reachable without overlap at the maximum font scale.
                    confirm?.let { PrimaryButton(it.first, it.second, modifier = Modifier.fillMaxWidth()) }
                    SecondaryButton(closeLabel, onClose, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Process-local state with bounded line layout, suitable for a scrollable reply edit dialog. */
@Composable fun ReplyEditField(state: androidx.compose.foundation.text.input.TextFieldState, label: String) {
    OutlinedTextField(state = state, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
        lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 6))
}
