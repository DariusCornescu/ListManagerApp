package com.darius.listmanager.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFormRulesTest {
    @Test
    fun `login requires only username and password`() {
        assertTrue(
            isAuthSubmitEnabled(
                isRegisterMode = false,
                username = "user",
                email = "",
                password = "secret",
                confirmPassword = ""
            )
        )
    }

    @Test
    fun `login rejects a missing username or password`() {
        assertFalse(isAuthSubmitEnabled(false, "", "", "secret", ""))
        assertFalse(isAuthSubmitEnabled(false, "user", "", "", ""))
    }

    @Test
    fun `registration requires email and matching confirmation`() {
        assertFalse(isAuthSubmitEnabled(true, "user", "", "secret", "secret"))
        assertFalse(isAuthSubmitEnabled(true, "user", "user@example.com", "secret", "different"))
        assertTrue(isAuthSubmitEnabled(true, "user", "user@example.com", "secret", "secret"))
    }
}
