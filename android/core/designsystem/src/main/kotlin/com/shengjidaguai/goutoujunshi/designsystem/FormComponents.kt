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
