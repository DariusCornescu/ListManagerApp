package com.darius.listmanager.ui.viewmodel

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileUpdateRequestTest {
    private val input = ProfileInput(email = " a@b.ro ", fullName = " Ana ", company = "", address = "Cluj")

    @Test fun trimsValues_andKeepsAnEmptyFieldSoTheServerClearsIt() {
        val request = input.toRequest(newPassword = "")
        assertEquals("a@b.ro", request.email)
        assertEquals("Ana", request.full_name)
        assertEquals("", request.company)
        assertEquals("Cluj", request.address)
    }

    @Test fun blankPassword_isNotSent() {
        assertNull(input.toRequest(newPassword = "").password)
        assertFalse(Gson().toJson(input.toRequest(newPassword = "")).contains("password"))
    }

    @Test fun blankEmail_isNotSent_soItCannotFailValidation() {
        assertNull(input.copy(email = "  ").toRequest(newPassword = "").email)
    }

    @Test fun newPassword_isSentAsTyped() {
        assertEquals(" pass word ", input.toRequest(newPassword = " pass word ").password)
    }
}
