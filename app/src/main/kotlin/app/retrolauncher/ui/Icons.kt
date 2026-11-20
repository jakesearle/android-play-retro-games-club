package app.retrolauncher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Material icons that aren't in material-icons-core, drawn from their Material path data
 * (Apache-2.0) instead of pulling in the whole extended icon set.
 */
object AppIcons {
    /** Material "history": a clock with a counter-clockwise arrow. */
    val History: ImageVector by lazy {
        icon(
            "History",
            "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 " +
                "-3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 " +
                "9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z",
        )
    }

    /** Material "filter_list": three lines, shortest at the bottom. */
    val FilterList: ImageVector by lazy {
        icon("FilterList", "M10,18h4v-2h-4v2zM3,6v2h18L21,6L3,6zM6,13h12v-2L6,11v2z")
    }

    /** Material "casino": a die showing five. */
    val Dice: ImageVector by lazy {
        icon(
            "Dice",
            "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM7.5,18C6.67,18 6,17.33 6,16.5" +
                "S6.67,15 7.5,15S9,15.67 9,16.5S8.33,18 7.5,18zM7.5,9C6.67,9 6,8.33 6,7.5S6.67,6 7.5,6S9,6.67 9,7.5S8.33,9 7.5,9z" +
                "M12,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5s1.5,0.67 1.5,1.5S12.83,13.5 12,13.5zM16.5,18" +
                "c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5s1.5,0.67 1.5,1.5S17.33,18 16.5,18zM16.5,9C15.67,9 15,8.33 15,7.5" +
                "S15.67,6 16.5,6S18,6.67 18,7.5S17.33,9 16.5,9z",
        )
    }

    private fun icon(name: String, path: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
