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
}
