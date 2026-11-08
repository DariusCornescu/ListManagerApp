package com.darius.listmanager.util

object SimilarityEngine {
    // Compiled once: phoneticKey runs for every product and alias on each voice match.
    private val VOWELS = Regex("[aeiou]+")
    private val C_OR_K = Regex("c|k")
    private val S_OR_Z = Regex("s|z")
    private val D_OR_T = Regex("d|t")
    private val B_OR_P = Regex("b|p")
    private val G_OR_J = Regex("g|j")
    private val REPEATED_LETTER = Regex("([A-Z])\\1+")

    fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        // Two rows of the edit-distance table are enough, and far cheaper to allocate.
        var previous = IntArray(len2 + 1) { it }
        var current = IntArray(len2 + 1)

        for (i in 1..len1) {
            current[0] = i
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost
                )
            }
            val done = previous
            previous = current
            current = done
        }

        return previous[len2]
    }

    /**
     * The highest [levenshteinSimilarity] two strings of these lengths can reach: the edit
     * distance is at least the difference in length.
     */
    internal fun levenshteinSimilarityCeiling(len1: Int, len2: Int): Double {
        if (len1 == 0 || len2 == 0) return if (len1 == len2) 1.0 else 0.0
        val maxLen = maxOf(len1, len2)
        return 1.0 - (kotlin.math.abs(len1 - len2).toDouble() / maxLen)
    }

    fun levenshteinSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val maxLen = maxOf(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)

        return 1.0 - (distance.toDouble() / maxLen)
    }

    fun jaccardSimilarity(s1: String, s2: String, nGramSize: Int = 2): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        return nGramJaccard(generateNGrams(s1, nGramSize), generateNGrams(s2, nGramSize))
    }

    /** [jaccardSimilarity] for bigram sets computed ahead with [bigrams]. */
    internal fun jaccardOfBigrams(s1: String, s2: String, bigrams1: Set<String>, bigrams2: Set<String>): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        return nGramJaccard(bigrams1, bigrams2)
    }

    internal fun bigrams(text: String): Set<String> = generateNGrams(text, 2)

    private fun nGramJaccard(ngrams1: Set<String>, ngrams2: Set<String>): Double {
        if (ngrams1.isEmpty() && ngrams2.isEmpty()) return 1.0
        if (ngrams1.isEmpty() || ngrams2.isEmpty()) return 0.0

        return setOverlap(ngrams1, ngrams2)
    }

    /** |a ∩ b| / |a ∪ b| for non-empty sets, counted without building either set. */
    private fun setOverlap(a: Set<String>, b: Set<String>): Double {
        val (small, large) = if (a.size <= b.size) a to b else b to a
        val intersection = small.count { it in large }
        val union = a.size + b.size - intersection

        return intersection.toDouble() / union
    }

    private fun generateNGrams(text: String, n: Int): Set<String> {
        if (text.length < n) return setOf(text)

        return (0..text.length - n)
            .map { text.substring(it, it + n) }
            .toSet()
    }

    fun tokenSimilarity(s1: String, s2: String): Double =
        tokenSetSimilarity(TextNorm.tokenize(s1).toSet(), TextNorm.tokenize(s2).toSet())

    /** [tokenSimilarity] for token sets computed ahead with `TextNorm.tokenize(text).toSet()`. */
    internal fun tokenSetSimilarity(tokens1: Set<String>, tokens2: Set<String>): Double {
        if (tokens1.isEmpty() && tokens2.isEmpty()) return 1.0
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        return setOverlap(tokens1, tokens2)
    }

    fun numberSeqSimilarity(s1: String, s2: String): Double =
        numberListSimilarity(TextNorm.extractNumbers(s1), TextNorm.extractNumbers(s2))

    /** [numberSeqSimilarity] for number lists computed ahead with `TextNorm.extractNumbers`. */
    internal fun numberListSimilarity(nums1: List<String>, nums2: List<String>): Double {
        if (nums1.isEmpty() && nums2.isEmpty()) return 1.0
        if (nums1.isEmpty() || nums2.isEmpty()) return 0.0

        // Check if numbers match (order doesn't matter for this use case)
        val match = nums1.intersect(nums2.toSet()).size
        val total = maxOf(nums1.size, nums2.size)

        return match.toDouble() / total
    }

    fun phoneticKey(text: String): String {
        val normalized = TextNorm.normalize(text)

        return normalized
            .replace(VOWELS, "A") // All vowels to A
            .replace(C_OR_K, "C")       // C and K same
            .replace(S_OR_Z, "S")       // S and Z same
            .replace(D_OR_T, "T")       // D and T similar
            .replace(B_OR_P, "P")       // B and P similar
            .replace(G_OR_J, "G")       // G and J similar
            .replace(REPEATED_LETTER, "$1") // Remove duplicates
    }

    fun phoneticSimilarity(s1: String, s2: String): Double {
        val key1 = phoneticKey(s1)
        val key2 = phoneticKey(s2)

        return levenshteinSimilarity(key1, key2)
    }

    fun prefixSimilarity(s1: String, s2: String): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val shorter = if (s1.length < s2.length) s1 else s2
        val longer = if (s1.length < s2.length) s2 else s1

        var matchLen = 0
        for (i in shorter.indices) {
            if (shorter[i] == longer[i]) {
                matchLen++
            } else {
                break
            }
        }

        return matchLen.toDouble() / longer.length
    }
}