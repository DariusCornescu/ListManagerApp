package com.darius.listmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopPolicyTest {

    @Test
    fun crashShortlyAfterStart_isRapid() {
        assertTrue(CrashLoopPolicy.isRapid(0))
        assertTrue(CrashLoopPolicy.isRapid(59_999))
        assertFalse(CrashLoopPolicy.isRapid(60_000))
        assertFalse(CrashLoopPolicy.isRapid(3_600_000))
    }

    @Test
    fun rapidCrashesAccumulate_slowCrashResets() {
        assertEquals(1, CrashLoopPolicy.nextCount(rapid = true, previous = 0))
        assertEquals(2, CrashLoopPolicy.nextCount(rapid = true, previous = 1))
        assertEquals(3, CrashLoopPolicy.nextCount(rapid = true, previous = 2))
        assertEquals(1, CrashLoopPolicy.nextCount(rapid = false, previous = 2))
    }

    @Test
    fun healsOnlyAtThreshold() {
        assertFalse(CrashLoopPolicy.shouldHeal(0))
        assertFalse(CrashLoopPolicy.shouldHeal(2))
        assertTrue(CrashLoopPolicy.shouldHeal(3))
        assertTrue(CrashLoopPolicy.shouldHeal(7))
    }

    @Test
    fun threeRapidCrashes_endToEndCounting() {
        var count = 0
        repeat(3) { count = CrashLoopPolicy.nextCount(rapid = true, previous = count) }
        assertTrue(CrashLoopPolicy.shouldHeal(count))
    }

    @Test
    fun streakGoesStaleOnlyAfterTtl() {
        assertFalse(CrashLoopPolicy.isStreakStale(0))
        assertFalse(CrashLoopPolicy.isStreakStale(CrashLoopPolicy.STREAK_TTL_MS - 1))
        assertTrue(CrashLoopPolicy.isStreakStale(CrashLoopPolicy.STREAK_TTL_MS))
        // Wall clock moved backwards between crashes — keep the streak, never wipe early.
        assertFalse(CrashLoopPolicy.isStreakStale(-5_000))
    }

    @Test
    fun staleStreak_rapidCrashStartsFresh() {
        val week = 7L * 24 * 60 * 60 * 1000
        assertEquals(1, CrashLoopPolicy.nextCount(rapid = true, previous = 2, msSinceLastCrash = week))
        assertEquals(1, CrashLoopPolicy.nextCount(rapid = true, previous = 7, msSinceLastCrash = CrashLoopPolicy.STREAK_TTL_MS))
    }

    @Test
    fun freshStreak_rapidCrashStillAccumulates() {
        assertEquals(3, CrashLoopPolicy.nextCount(rapid = true, previous = 2, msSinceLastCrash = 30_000))
        assertEquals(3, CrashLoopPolicy.nextCount(rapid = true, previous = 2, msSinceLastCrash = CrashLoopPolicy.STREAK_TTL_MS - 1))
    }

    @Test
    fun unknownLastCrashTime_accumulatesAsBefore() {
        assertEquals(3, CrashLoopPolicy.nextCount(rapid = true, previous = 2, msSinceLastCrash = null))
    }

    @Test
    fun slowCrash_resetsToOne_regardlessOfStreakAge() {
        assertEquals(1, CrashLoopPolicy.nextCount(rapid = false, previous = 2, msSinceLastCrash = 10_000))
        assertEquals(1, CrashLoopPolicy.nextCount(rapid = false, previous = 2, msSinceLastCrash = CrashLoopPolicy.STREAK_TTL_MS * 2))
    }

    @Test
    fun rapidCrashesSpreadOverWeeks_neverHeal() {
        val week = 7L * 24 * 60 * 60 * 1000
        var count = 0
        var sinceLast: Long? = null
        repeat(3) {
            count = CrashLoopPolicy.nextCount(rapid = true, previous = count, msSinceLastCrash = sinceLast)
            sinceLast = week
        }
        assertFalse(CrashLoopPolicy.shouldHeal(count))
    }

    @Test
    fun liveCrashLoop_withTimestamps_stillHeals() {
        var count = 0
        var sinceLast: Long? = null
        repeat(3) {
            count = CrashLoopPolicy.nextCount(rapid = true, previous = count, msSinceLastCrash = sinceLast)
            sinceLast = 30_000 // user relaunched the broken app within seconds
        }
        assertTrue(CrashLoopPolicy.shouldHeal(count))
    }
}
