package app.voicetodo.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The few Material icons the core icon set lacks, from their standard
 * 24dp paths, rather than pulling in the multi-megabyte extended set.
 */
object Glyphs {
    val Download: ImageVector = materialIcon(name = "Filled.FileDownload") {
        materialPath {
            moveTo(19f, 9f); horizontalLineToRelative(-4f); verticalLineTo(3f); horizontalLineTo(9f)
            verticalLineToRelative(6f); horizontalLineTo(5f); lineToRelative(7f, 7f); lineToRelative(7f, -7f); close()
            moveTo(5f, 18f); verticalLineToRelative(2f); horizontalLineToRelative(14f); verticalLineToRelative(-2f); horizontalLineTo(5f); close()
        }
    }

    val Pause: ImageVector = materialIcon(name = "Filled.Pause") {
        materialPath {
            moveTo(6f, 19f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineTo(6f); verticalLineToRelative(14f); close()
            moveTo(14f, 5f); verticalLineToRelative(14f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineToRelative(-4f); close()
        }
    }

    val Stop: ImageVector = materialIcon(name = "Filled.Stop") {
        materialPath {
            moveTo(6f, 6f); horizontalLineToRelative(12f); verticalLineToRelative(12f); horizontalLineTo(6f); close()
        }
    }
}
