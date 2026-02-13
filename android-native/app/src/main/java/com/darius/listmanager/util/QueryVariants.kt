package com.darius.listmanager.util
import com.darius.listmanager.util.TextNorm

object QueryVariants {

    /**
     * Generate multiple query variants for better FTS matching
     * Handles: plurals, with/without 'x', prefixes, decimal variations
     */
    fun generate(spokenText: String): List<String> {
        val normalized = TextNorm.normalize(spokenText)
        val variants = mutableSetOf<String>()

        // Base normalized query
        variants.add(normalized)

        // Add individual tokens
        val tokens = TextNorm.tokenize(normalized)
        variants.addAll(tokens)

        // Generate variants with/without 'x' (for quantities like "5x bananas")
        if (normalized.contains(" x ") || normalized.contains("x")) {
            variants.add(normalized.replace(" x ", " "))
            variants.add(normalized.replace("x", ""))
        }

        // Handle decimal variations (e.g., "1.5" -> "1 5", "15")
        if (TextNorm.containsDigits(normalized)) {
            val numbers = TextNorm.extractNumbers(normalized)
            numbers.forEach { num ->
                if (num.contains(".")) {
                    // "1.5" -> "1 5"
                    val withoutDot = num.replace(".", " ")
                    variants.add(normalized.replace(num, withoutDot))
                    // "1.5" -> "15"
                    val collapsed = num.replace(".", "")
                    variants.add(normalized.replace(num, collapsed))
                }
            }
        }

        // Remove common prefixes/suffixes for better matching
        tokens.forEach { token ->
            // Remove quantity words
            val withoutQuantity = token
                .replace(Regex("^(un|o|doi|doua|trei|patru|cinci)\\s*"), "")
            if (withoutQuantity != token) {
                variants.add(withoutQuantity)
            }

            // Handle singular/plural variations (basic Romanian plurals)
            if (token.endsWith("i") && token.length > 2) {
                // "cartofi" -> "cartof"
                variants.add(token.dropLast(1))
            }
            if (token.endsWith("e") && token.length > 2) {
                // "oua" variations
                variants.add(token.dropLast(1))
            }
            if (token.endsWith("uri") && token.length > 4) {
                // "legume" -> "leguma"
                variants.add(token.dropLast(3) + "a")
            }
        }

        // Create wildcard variants for FTS
        tokens.forEach { token ->
            if (token.length >= 3) {
                variants.add("$token*") // Prefix match
            }
        }

        return variants.filter { it.isNotBlank() }.distinct()
    }

    /**
     * Build FTS query string from variants
     * Uses OR logic to match any variant
     */
    fun toFtsQuery(variants: List<String>): String {
        return variants.joinToString(" OR ") { variant ->
            // Escape special FTS characters except *
            val escaped = variant.replace("\"", "\"\"")
            if (variant.endsWith("*")) {
                escaped
            } else {
                "\"$escaped\""
            }
        }
    }
}