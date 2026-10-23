package wpm.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import wpm.ui.AppColors
import wpm.ui.Theme

/*
 * The "Origami Fold" building blocks: a sheet with one folded-down corner, the flap drawn
 * into that corner, a diagonal crease of light across filled surfaces and the faceted
 * paper background.
 */

/** A sheet with softly rounded corners whose top-right corner is folded away by [fold]. */
@Immutable
class FoldShape(private val fold: Dp, private val radius: Dp = 4.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val f = with(density) { fold.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2)
        val r = with(density) { radius.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2)
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w - f, 0f)
            lineTo(w, f)
            lineTo(w, h - r)
            quadraticBezierTo(w, h, w - r, h)
            lineTo(r, h)
            quadraticBezierTo(0f, h, 0f, h - r)
            lineTo(0f, r)
            quadraticBezierTo(0f, 0f, r, 0f)
            close()
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?) = other is FoldShape && other.fold == fold && other.radius == radius
    override fun hashCode() = fold.hashCode() * 31 + radius.hashCode()
}

/** The folded-down corner of a [FoldShape]: its shadow, then the paper's underside. */
fun DrawScope.drawFoldFlap(fold: Float, flap: Color, shadow: Color) {
    if (fold <= 0.5f) return
    val w = size.width
    val shadowPath = Path().apply {
        moveTo(w - fold, 0f)
        lineTo(w - fold - fold * 0.12f, fold * 1.18f)
        lineTo(w, fold)
        close()
    }
    drawPath(shadowPath, shadow.copy(alpha = 0.16f))
    val flapPath = Path().apply {
        moveTo(w - fold, 0f)
        lineTo(w - fold, fold)
        lineTo(w, fold)
        close()
    }
    drawPath(
        flapPath,
        Brush.linearGradient(listOf(flap, lerpColor(flap, Color.Black, 0.10f)), Offset(w - fold, fold), Offset(w - fold / 2, fold / 2)),
    )
}

/** A soft diagonal band of light across a filled surface, like the lit half of a folded sheet. */
fun Modifier.crease(alpha: Float = 0.16f): Modifier = drawWithCache {
    val lit = Path().apply {
        moveTo(0f, 0f)
        lineTo(size.width * 0.62f, 0f)
        lineTo(size.width * 0.38f, size.height)
        lineTo(0f, size.height)
        close()
    }
    onDrawBehind { drawPath(lit, Color.White.copy(alpha = alpha)) }
}

/** Large, quiet paper facets in the corners of the page background. */
fun Modifier.facetBackground(colors: AppColors): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val facets = listOf(
        // top right: a fan of folds
        listOf(Offset(w * 0.62f, 0f), Offset(w, 0f), Offset(w * 0.86f, h * 0.30f)) to 1.0f,
        listOf(Offset(w, 0f), Offset(w, h * 0.42f), Offset(w * 0.86f, h * 0.30f)) to 0.65f,
        listOf(Offset(w * 0.62f, 0f), Offset(w * 0.86f, h * 0.30f), Offset(w * 0.74f, h * 0.18f)) to 0.45f,
        // bottom left: a single folded wing
        listOf(Offset(0f, h * 0.66f), Offset(w * 0.20f, h), Offset(0f, h)) to 0.85f,
        listOf(Offset(0f, h * 0.66f), Offset(w * 0.11f, h * 0.80f), Offset(w * 0.20f, h)) to 0.5f,
    ).map { (points, strength) ->
        Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        } to strength
    }
    val base = colors.windowBg
    onDrawBehind {
        drawRect(base)
        facets.forEach { (path, strength) -> drawPath(path, lerpColor(base, colors.facet, strength)) }
    }
}

/** A thin fold line that fades out to the right, used under page titles. */
fun Modifier.creaseLine(color: Color): Modifier = drawBehind {
    drawLine(
        Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f))),
        Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx(),
    )
}

/** The app's mark: a folded paper crane's body seen from above, made of four shaded facets. */
@Composable
fun OrigamiMark(size: Dp, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val top = Offset(w * 0.5f, 0f)
        val right = Offset(w, h * 0.5f)
        val bottom = Offset(w * 0.5f, h)
        val left = Offset(0f, h * 0.5f)
        val mid = Offset(w * 0.5f, h * 0.5f)
        fun tri(a: Offset, b: Offset, d: Offset, color: Color) = drawPath(Path().apply {
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); close()
        }, color)
        tri(left, top, mid, c.accent)
        tri(top, right, mid, lerpColor(c.accent, Color.White, 0.28f))
        tri(right, bottom, mid, c.accentDeep)
        tri(bottom, left, mid, c.accentPair)
    }
}

/** A small folded triangle used as a bullet before eyebrows and section titles. */
@Composable
fun FoldGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawPath(Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(0f, h); close() }, color)
        drawPath(Path().apply { moveTo(w, 0f); lineTo(w, h); lineTo(0f, h); close() }, color.copy(alpha = 0.45f))
    }
}

fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
