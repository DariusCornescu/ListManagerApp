package com.darius.listmanager.util
import java.text.Normalizer
import java.util.Locale

object TextNorm {
    fun normalize(text: String): String {
        return text
            .lowercase(Locale.getDefault())
            .removeDiacritics()
            .normalizeDimensionSeparators()
            .trim()
            .replace(Regex("\\s+"), " ")
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
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    private fun String.normalizeDimensionSeparators(): String {
        var result = this
            .replace("×", "x")
            .replace("*", "x")
            .replace(" pe ", "x")
            .replace(Regex("(\\d)\\s*x\\s*(\\d)"), "$1x$2")

        result = result.replace(
            Regex("(\\d{1,3})\\s+(\\d{2,4})(?=\\s|$)"),
            "$1x$2"
        )
        return result
    }

    fun tokenize(text: String): List<String> {
        return normalize(text)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }

    fun containsDigits(text: String): Boolean {
        return text.any { it.isDigit() }
    }

    fun extractNumbers(text: String): List<String> {
        return Regex("\\d+\\.?\\d*").findAll(text)
            .map { it.value }
            .toList()
    }

    fun extractDimensions(text: String): List<String> {
        val normalized = normalize(text)
        val dimensions = mutableSetOf<String>()

        Regex("\\d+\\.?\\d*x\\d+\\.?\\d*(?:x\\d+\\.?\\d*)?")
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