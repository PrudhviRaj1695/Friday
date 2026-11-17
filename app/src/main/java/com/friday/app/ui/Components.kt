package com.friday.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun FxCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Fx.Surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Fx.Outline)
    ) { Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Fx.TextTertiary, modifier = modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Compact status pill used in the command-center header. */
@Composable
fun StatusChip(label: String, value: String, dot: Color? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(
        color = Fx.SurfaceHigh,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Fx.Outline),
        modifier = modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick) else Modifier)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) { StatusDot(dot); Spacer(Modifier.width(8.dp)) }
            if (icon != null) { Icon(icon, null, tint = Fx.TextSecondary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Fx.TextTertiary, maxLines = 1)
                Text(value, style = MaterialTheme.typography.labelLarge, color = Fx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun FxPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Fx.Blue, contentColor = Color.White, disabledContainerColor = Fx.SurfaceHigher, disabledContentColor = Fx.TextTertiary),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    ) {
        if (icon != null) { Icon(icon, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun FxSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, danger: Boolean = false) {
    val color = if (danger) Fx.Danger else Fx.Text
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (danger) Fx.Danger.copy(alpha = .5f) else Fx.OutlineStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = Fx.TextTertiary),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (icon != null) { Icon(icon, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Fx.Text else Fx.TextTertiary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Fx.Blue, checkedThumbColor = Color.White, uncheckedTrackColor = Fx.SurfaceHigher, uncheckedBorderColor = Fx.OutlineStrong, uncheckedThumbColor = Fx.TextSecondary))
    }
}

@Composable
fun LabeledSlider(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, enabled: Boolean = true, help: String? = null, onChange: (Float) -> Unit, onDone: (() -> Unit)? = null) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = if (enabled) Fx.Text else Fx.TextTertiary)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = Fx.Cyan)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Fx.Blue, inactiveTrackColor = Fx.SurfaceHigher, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
        )
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall)
    }
}

/** Single-select segmented control. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: (T) -> Boolean = { true }) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Fx.SurfaceHigh).border(1.dp, Fx.Outline, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val isSel = option == selected
            val on = enabled(option)
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) Fx.Blue else Color.Transparent)
                    .clickable(enabled = on, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label(option), style = MaterialTheme.typography.labelLarge, color = when { isSel -> Color.White; on -> Fx.TextSecondary; else -> Fx.TextTertiary }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun FxTextField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1, placeholder: String? = null, visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None, keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier.fillMaxWidth(),
        singleLine = singleLine, minLines = minLines, maxLines = if (singleLine) 1 else 10,
        placeholder = placeholder?.let { { Text(it, color = Fx.TextTertiary) } },
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Fx.Blue, unfocusedBorderColor = Fx.OutlineStrong, focusedLabelColor = Fx.Cyan, unfocusedLabelColor = Fx.TextSecondary, cursorColor = Fx.Cyan, focusedContainerColor = Fx.Background, unfocusedContainerColor = Fx.Background)
    )
}

/** Inline notice: info (blue), warning (amber) or error (red). */
@Composable
fun Notice(text: String, tone: Color = Fx.Blue, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = .10f)).border(1.dp, tone.copy(alpha = .35f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(tone))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Fx.Text, modifier = Modifier.weight(1f))
        if (action != null) { Spacer(Modifier.width(8.dp)); action() }
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Fx.Text) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}
