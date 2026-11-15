package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CodeFontFamily
import com.example.ui.theme.CodeSurface
import com.example.ui.theme.CoralError
import com.example.ui.theme.DiffAddedBg
import com.example.ui.theme.DiffRemovedBg
import com.example.ui.theme.EmeraldSecondary

private enum class DiffLineKind { CONTEXT, REMOVED, ADDED }
private data class DiffLine(val kind: DiffLineKind, val text: String)

@Composable
fun DiffViewer(
    beforeText: String,
    afterText: String,
    modifier: Modifier = Modifier,
) {
    val diffLines = remember(beforeText, afterText) { computeLineDiff(beforeText, afterText) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CodeSurface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        diffLines.forEach { line ->
            val bg = when (line.kind) {
                DiffLineKind.ADDED -> DiffAddedBg
                DiffLineKind.REMOVED -> DiffRemovedBg
                DiffLineKind.CONTEXT -> Color.Transparent
            }
            val fg = when (line.kind) {
                DiffLineKind.ADDED -> EmeraldSecondary
                DiffLineKind.REMOVED -> CoralError
                DiffLineKind.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val prefix = when (line.kind) {
                DiffLineKind.ADDED -> "+ "
                DiffLineKind.REMOVED -> "- "
                DiffLineKind.CONTEXT -> "  "
            }
            Text(
                text = prefix + line.text,
                fontFamily = CodeFontFamily,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = fg,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 10.dp, vertical = 1.dp),
            )
        }
    }
}

private fun computeLineDiff(before: String, after: String): List<DiffLine> {
    val oldLines = if (before.isBlank()) emptyList() else before.lines()
    val newLines = if (after.isBlank()) emptyList() else after.lines()
    val oldSet = oldLines.toSet()
    val newSet = newLines.toSet()

    val result = mutableListOf<DiffLine>()
    for (line in oldLines) {
        if (line in newSet) {
            result.add(DiffLine(DiffLineKind.CONTEXT, line))
        } else {
            result.add(DiffLine(DiffLineKind.REMOVED, line))
        }
    }
    for (line in newLines) {
        if (line !in oldSet) {
            result.add(DiffLine(DiffLineKind.ADDED, line))
        }
    }
    return result.take(120)
}
