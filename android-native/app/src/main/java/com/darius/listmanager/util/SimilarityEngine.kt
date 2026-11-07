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

        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[len1][len2]
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

        val ngrams1 = generateNGrams(s1, nGramSize)
        val ngrams2 = generateNGrams(s2, nGramSize)

        if (ngrams1.isEmpty() && ngrams2.isEmpty()) return 1.0
        if (ngrams1.isEmpty() || ngrams2.isEmpty()) return 0.0

        val intersection = ngrams1.intersect(ngrams2).size
        val union = ngrams1.union(ngrams2).size

        return intersection.toDouble() / union
    }

    private fun generateNGrams(text: String, n: Int): Set<String> {
        if (text.length < n) return setOf(text)

        return (0..text.length - n)
            .map { text.substring(it, it + n) }
            .toSet()
    }

    fun tokenSimilarity(s1: String, s2: String): Double {
        val tokens1 = TextNorm.tokenize(s1).toSet()
        val tokens2 = TextNorm.tokenize(s2).toSet()

        if (tokens1.isEmpty() && tokens2.isEmpty()) return 1.0
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        val intersection = tokens1.intersect(tokens2).size
        val union = tokens1.union(tokens2).size

        return intersection.toDouble() / union
    }

    fun numberSeqSimilarity(s1: String, s2: String): Double {
        val nums1 = TextNorm.extractNumbers(s1)
        val nums2 = TextNorm.extractNumbers(s2)

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