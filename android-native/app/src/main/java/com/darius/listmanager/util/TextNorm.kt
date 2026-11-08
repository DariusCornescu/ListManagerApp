package com.darius.listmanager.util
import java.text.Normalizer
import java.util.Locale

object TextNorm {
    // Compiled once: these run many times for every product on each voice match.
    private val WHITESPACE = Regex("\\s+")
    private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val DIGITS_AROUND_X = Regex("(\\d)\\s*x\\s*(\\d)")
    private val SPACED_DIMENSION = Regex("(\\d{1,3})\\s+(\\d{2,4})(?=\\s|$)")
    private val NUMBER = Regex("\\d+\\.?\\d*")
    private val DIMENSION = Regex("\\d+\\.?\\d*x\\d+\\.?\\d*(?:x\\d+\\.?\\d*)?")
    private val DECIMAL_COMMA = Regex("(?<=\\d),(?=\\d)")

    fun normalize(text: String): String {
        return text
            .lowercase(Locale.getDefault())
            .removeDiacritics()
            .normalizeDimensionSeparators()
            .trim()
            .replace(WHITESPACE, " ")
    }

    private fun String.removeDiacritics(): String {
        val result = this
            .replace("ă", "a")
            .replace("â", "a")
            .replace("î", "i")
            .replace("ș", "s")
            .replace("ț", "t")
            .replace("Ă", "a")
            .replace("Â", "a")
            .replace("Î", "i")
            .replace("Ș", "s")
            .replace("Ț", "t")

        val normalized = Normalizer.normalize(result, Normalizer.Form.NFD)
        return normalized.replace(COMBINING_MARKS, "")
    }

    private fun String.normalizeDimensionSeparators(): String {
        var result = this
            .replace("×", "x")
            .replace("*", "x")
            .replace(" pe ", "x")
            .replace(DIGITS_AROUND_X, "$1x$2")

        result = result.replace(SPACED_DIMENSION, "$1x$2")
        return result
    }

    /**
     * A normalized word without its Romanian ending, so "cameră", "camere" and "camerei" share
     * one, as do "șurub" and "șuruburi". Words under five letters and anything with a digit
     * are returned as they are.
     */
    fun wordStem(token: String): String {
        if (token.length < 5 || !token.all { it in 'a'..'z' }) return token
        if (token.endsWith("uri") && token.length - 3 >= 4) return token.dropLast(3)
        val stem = token.trimEnd('a', 'e', 'i', 'o', 'u')
        return if (stem.length >= 4) stem else token
    }

    /**
     * The sizes written in a normalized text, without decimal dots: "26*1.75" and a spoken
     * "26 * 175" both give "26x175", because the recognizer often loses the dot. A size with
     * three parts also gives its first two, so "10.5x5.2" finds "10.5x5.2x2.1".
     */
    fun sizes(normalized: String): Set<String> =
        DIMENSION.findAll(normalized.replace(DECIMAL_COMMA, "."))
            .flatMap { match ->
                val parts = match.value.split('x').map { it.replace(".", "") }
                listOfNotNull(parts.joinToString("x"), parts.take(2).joinToString("x").takeIf { parts.size > 2 })
            }
            .toSet()

    fun tokenize(text: String): List<String> {
        return normalize(text)
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
    }

    fun containsDigits(text: String): Boolean {
        return text.any { it.isDigit() }
    }

    fun extractNumbers(text: String): List<String> {
        return NUMBER.findAll(text)
            .map { it.value }
            .toList()
    }

    fun extractDimensions(text: String): List<String> {
        val normalized = normalize(text)
        val dimensions = mutableSetOf<String>()

        DIMENSION
            .findAll(normalized)
            .forEach { dimensions.add(it.value) }

        val numbers = extractNumbers(normalized)
        if (numbers.size >= 2) {
            for (i in 0 until numbers.size - 1) {
                val num1 = numbers[i]
                val num2 = numbers[i + 1]

                if (couldBeDimension(num1) && couldBeDimension(num2)) {
                    val dim1 = tryInsertDecimal(num1)
                    val dim2 = tryInsertDecimal(num2)

                    dimensions.add("${num1}x${num2}")
                    if (dim1 != num1 || dim2 != num2) {
                        dimensions.add("${dim1}x${dim2}")
                    }
                }
            }
        }

        return dimensions.toList()
    }

    private fun couldBeDimension(numStr: String): Boolean {
        val num = numStr.toDoubleOrNull() ?: return false
        return num in 0.1..9999.0 && numStr.length <= 5
    }

    private fun tryInsertDecimal(numStr: String): String {
        if (numStr.contains(".")) return numStr
        val len = numStr.length

        when (len) {
            3 -> {
                val firstTwo = numStr.substring(0, 2).toIntOrNull() ?: return numStr
                val lastOne = numStr.substring(2, 3).toIntOrNull() ?: return numStr
                if (firstTwo in 10..99 && lastOne in 0..9) {
                    return "${numStr.substring(0, 2)}.${numStr.substring(2)}"
                }
            }
            4 -> { return numStr }
        }
        return numStr
    }

    fun hasDimensions(text: String): Boolean {
        return extractDimensions(text).isNotEmpty()
    }

    fun normalizePotentialDimension(text: String): String {
        val normalized = normalize(text)

        val existingDims = extractDimensions(normalized)
        if (existingDims.isNotEmpty()) {
            return existingDims.first()
        }

        val numbers = extractNumbers(normalized)
        if (numbers.size >= 2) {
            val num1 = tryInsertDecimal(numbers[0])
            val num2 = tryInsertDecimal(numbers[1])

            if (numbers.size == 3) {
                val num3 = tryInsertDecimal(numbers[2])
                return "${num1}x${num2}x${num3}"
            }
            return "${num1}x${num2}"
        }

        return normalized
    }
}