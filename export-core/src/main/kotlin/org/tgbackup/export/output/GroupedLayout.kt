// Port of tdesktop Telegram/SourceFiles/ui/grouped_layout.cpp (geometry only).
package org.tgbackup.export.output

internal data class QSize(val width: Int, val height: Int) {
    fun isEmpty() = width <= 0 || height <= 0
}

internal data class QRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun isNull() = width == 0 && height == 0
    fun isEmpty() = width <= 0 || height <= 0
    val right get() = x + width
    val bottom get() = y + height

    fun united(r: QRect): QRect {
        if (isNull()) return r
        if (r.isNull()) return this
        val l = minOf(x, r.x)
        val t = minOf(y, r.y)
        val rr = maxOf(right, r.right)
        val b = maxOf(bottom, r.bottom)
        return QRect(l, t, rr - l, b - t)
    }

    companion object {
        val NULL = QRect(0, 0, 0, 0)
    }
}

/** base::SafeRound: std::round (half away from zero). */
internal fun SafeRound(value: Double): Int = Math.round(Math.abs(value)).let { if (value < 0) -it else it }.toInt()

internal fun LayoutMediaGroupGeometry(sizes: List<QSize>, maxWidth: Int, minWidth: Int, spacing: Int): List<QRect> =
    Layouter(sizes, maxWidth, minWidth, spacing).layout()

private class Layouter(private val sizes: List<QSize>, maxWidth: Int, minWidth: Int, spacing: Int) {
    private val ratios = sizes.map { it.width / it.height.toDouble() }
    private val proportions = ratios.map { if (it > 1.2) 'w' else if (it < 0.8) 'n' else 'q' }.joinToString("")
    private val count = ratios.size
    private val maxWidth = maxWidth
    private val maxHeight = maxWidth
    private val minWidth = minWidth
    private val spacing = spacing
    private val averageRatio = ratios.fold(1.0) { a, b -> a + b } / count
    private val maxSizeRatio = maxWidth / maxHeight.toDouble()

    fun layout(): List<QRect> {
        if (count == 0) return emptyList()
        if (count == 1) return layoutOne()
        if (count >= 5 || ratios.any { it > 2 }) {
            return ComplexLayouter(ratios, averageRatio, maxWidth, minWidth, spacing).layout()
        }
        return when (count) {
            2 -> layoutTwo()
            3 -> if (proportions[0] == 'n') layoutThreeLeftAndOther() else layoutThreeTopAndOther()
            else -> if (proportions[0] == 'w') layoutFourTopAndOther() else layoutFourLeftAndOther()
        }
    }

    private fun layoutTwo(): List<QRect> = when {
        proportions == "ww" && averageRatio > 1.4 * maxSizeRatio && ratios[1] - ratios[0] < 0.2 -> layoutTwoTopBottom()
        proportions == "ww" || proportions == "qq" -> layoutTwoLeftRightEqual()
        else -> layoutTwoLeftRight()
    }

    private fun layoutOne(): List<QRect> {
        val width = maxWidth
        val height = (sizes[0].height * width) / sizes[0].width
        return listOf(QRect(0, 0, width, height))
    }

    private fun layoutTwoTopBottom(): List<QRect> {
        val width = maxWidth
        val height = SafeRound(minOf(width / ratios[0], minOf(width / ratios[1], (maxHeight - spacing) / 2.0)))
        return listOf(QRect(0, 0, width, height), QRect(0, height + spacing, width, height))
    }

    private fun layoutTwoLeftRightEqual(): List<QRect> {
        val width = (maxWidth - spacing) / 2
        val height = SafeRound(minOf(width / ratios[0], minOf(width / ratios[1], maxHeight * 1.0)))
        return listOf(QRect(0, 0, width, height), QRect(width + spacing, 0, width, height))
    }

    private fun layoutTwoLeftRight(): List<QRect> {
        val minimalWidth = SafeRound(minWidth * 1.5)
        val secondWidth = minOf(
            SafeRound(maxOf(0.4 * (maxWidth - spacing), (maxWidth - spacing) / ratios[0] / (1.0 / ratios[0] + 1.0 / ratios[1]))),
            maxWidth - spacing - minimalWidth,
        )
        val firstWidth = maxWidth - secondWidth - spacing
        val height = minOf(maxHeight, SafeRound(minOf(firstWidth / ratios[0], secondWidth / ratios[1])))
        return listOf(QRect(0, 0, firstWidth, height), QRect(firstWidth + spacing, 0, secondWidth, height))
    }

    private fun layoutThreeLeftAndOther(): List<QRect> {
        val firstHeight = maxHeight
        val thirdHeight = SafeRound(minOf((maxHeight - spacing) / 2.0, ratios[1] * (maxWidth - spacing) / (ratios[2] + ratios[1])))
        val secondHeight = firstHeight - thirdHeight - spacing
        val rightWidth = maxOf(
            minWidth,
            SafeRound(minOf((maxWidth - spacing) / 2.0, minOf(thirdHeight * ratios[2], secondHeight * ratios[1]))),
        )
        val leftWidth = minOf(SafeRound(firstHeight * ratios[0]), maxWidth - spacing - rightWidth)
        return listOf(
            QRect(0, 0, leftWidth, firstHeight),
            QRect(leftWidth + spacing, 0, rightWidth, secondHeight),
            QRect(leftWidth + spacing, secondHeight + spacing, rightWidth, thirdHeight),
        )
    }

    private fun layoutThreeTopAndOther(): List<QRect> {
        val firstWidth = maxWidth
        val firstHeight = SafeRound(minOf(firstWidth / ratios[0], (maxHeight - spacing) * 0.66))
        val secondWidth = (maxWidth - spacing) / 2
        val secondHeight = minOf(maxHeight - firstHeight - spacing, SafeRound(minOf(secondWidth / ratios[1], secondWidth / ratios[2])))
        val thirdWidth = firstWidth - secondWidth - spacing
        return listOf(
            QRect(0, 0, firstWidth, firstHeight),
            QRect(0, firstHeight + spacing, secondWidth, secondHeight),
            QRect(secondWidth + spacing, firstHeight + spacing, thirdWidth, secondHeight),
        )
    }

    private fun layoutFourTopAndOther(): List<QRect> {
        val w = maxWidth
        val h0 = SafeRound(minOf(w / ratios[0], (maxHeight - spacing) * 0.66))
        val h = SafeRound((maxWidth - 2 * spacing) / (ratios[1] + ratios[2] + ratios[3]))
        val w0 = maxOf(minWidth, SafeRound(minOf((maxWidth - 2 * spacing) * 0.4, h * ratios[1])))
        val w2 = SafeRound(maxOf(maxOf(minWidth * 1.0, (maxWidth - 2 * spacing) * 0.33), h * ratios[3]))
        val w1 = w - w0 - w2 - 2 * spacing
        val h1 = minOf(maxHeight - h0 - spacing, h)
        return listOf(
            QRect(0, 0, w, h0),
            QRect(0, h0 + spacing, w0, h1),
            QRect(w0 + spacing, h0 + spacing, w1, h1),
            QRect(w0 + spacing + w1 + spacing, h0 + spacing, w2, h1),
        )
    }

    private fun layoutFourLeftAndOther(): List<QRect> {
        val h = maxHeight
        val w0 = SafeRound(minOf(h * ratios[0], (maxWidth - spacing) * 0.6))
        val w = SafeRound((maxHeight - 2 * spacing) / (1.0 / ratios[1] + 1.0 / ratios[2] + 1.0 / ratios[3]))
        val h0 = SafeRound(w / ratios[1])
        val h1 = SafeRound(w / ratios[2])
        val h2 = h - h0 - h1 - 2 * spacing
        val w1 = maxOf(minWidth, minOf(maxWidth - w0 - spacing, w))
        return listOf(
            QRect(0, 0, w0, h),
            QRect(w0 + spacing, 0, w1, h0),
            QRect(w0 + spacing, h0 + spacing, w1, h1),
            QRect(w0 + spacing, h0 + h1 + 2 * spacing, w1, h2),
        )
    }
}

private class ComplexLayouter(
    ratios: List<Double>,
    private val averageRatio: Double,
    private val maxWidth: Int,
    private val minWidth: Int,
    private val spacing: Int,
) {
    private val ratios = ratios.map { ratio ->
        if (averageRatio > 1.1) ratio.coerceIn(1.0, 2.75) else ratio.coerceIn(0.6667, 1.0)
    }
    private val count = this.ratios.size
    private val maxHeight = maxWidth * 4 / 3

    private class Attempt(val lineCounts: List<Int>, val heights: List<Double>)

    fun layout(): List<QRect> {
        check(count > 1)
        val result = arrayOfNulls<QRect>(count)
        val attempts = ArrayList<Attempt>()
        fun multiHeight(offset: Int, count: Int): Double {
            var sum = 0.0
            for (i in offset until offset + count) sum += ratios[i]
            return (maxWidth - (count - 1) * spacing) / sum
        }
        fun pushAttempt(lineCounts: List<Int>) {
            val heights = ArrayList<Double>()
            var offset = 0
            for (c in lineCounts) {
                heights.add(multiHeight(offset, c))
                offset += c
            }
            attempts.add(Attempt(lineCounts, heights))
        }
        for (first in 1 until count) {
            val second = count - first
            if (first > 3 || second > 3) continue
            pushAttempt(listOf(first, second))
        }
        for (first in 1 until count - 1) {
            for (second in 1 until count - first) {
                val third = count - first - second
                if (first > 3 || second > (if (averageRatio < 0.85) 4 else 3) || third > 3) continue
                pushAttempt(listOf(first, second, third))
            }
        }
        for (first in 1 until count - 1) {
            for (second in 1 until count - first) {
                for (third in 1 until count - first - second) {
                    val fourth = count - first - second - third
                    if (first > 3 || second > 3 || third > 3 || fourth > 3) continue
                    pushAttempt(listOf(first, second, third, fourth))
                }
            }
        }
        var optimal: Attempt? = null
        var optimalDiff = 0.0
        for (attempt in attempts) {
            val heights = attempt.heights
            val counts = attempt.lineCounts
            val lineCount = counts.size
            val totalHeight = heights.sum() + spacing * (lineCount - 1)
            val minLineHeight = heights.min()
            val bad1 = if (minLineHeight < minWidth) 1.5 else 1.0
            var bad2 = 1.0
            for (line in 1 until lineCount) {
                if (counts[line - 1] > counts[line]) {
                    bad2 = 1.5
                    break
                }
            }
            val diff = Math.abs(totalHeight - maxHeight) * bad1 * bad2
            if (optimal == null || diff < optimalDiff) {
                optimal = attempt
                optimalDiff = diff
            }
        }
        val best = optimal!!
        var index = 0
        var y = 0.0
        for (row in best.lineCounts.indices) {
            val colCount = best.lineCounts[row]
            val lineHeight = best.heights[row]
            val height = SafeRound(lineHeight)
            var x = 0
            for (col in 0 until colCount) {
                val ratio = ratios[index]
                val width = if (col == colCount - 1) maxWidth - x else SafeRound(ratio * lineHeight)
                result[index] = QRect(x, y.toInt(), width, height)
                x += width + spacing
                ++index
            }
            y += height + spacing
        }
        return result.map { it!! }
    }
}
