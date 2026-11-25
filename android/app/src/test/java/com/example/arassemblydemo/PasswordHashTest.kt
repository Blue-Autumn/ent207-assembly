package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class PasswordHashTest {
    @Test fun saltIsRandomAndPasswordMustMatchExactly() {
        val a = PasswordHash.create("test-password-123")
        val b = PasswordHash.create("test-password-123")
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.digest, b.digest)
        assertTrue(PasswordHash.verify("test-password-123", a))
        assertFalse(PasswordHash.verify("test-password-124", a))
        assertFalse(PasswordHash.verify(" test-password-123", a))
        assertFalse(a.digest.contains("test-password"))
    }

    @Test fun malformedAndUnexpectedWorkFactorsFailClosed() {
        val a = PasswordHash.create("test-password")
        for (record in listOf(a.copy(iterations = Int.MAX_VALUE), a.copy(iterations = 1),
            a.copy(salt = "?"), a.copy(digest = ""))) {
            assertFalse(PasswordHash.valid(record))
            assertFalse(PasswordHash.verify("test-password", record))
        }
    }

    @Test fun passwordLimitsDoNotSilentlyTruncate() {
        val a = PasswordHash.create("中文演示密码123")
        assertTrue(PasswordHash.verify("中文演示密码123", a))
        assertFalse(PasswordHash.verify("short", a))
        assertFalse(PasswordHash.verify("x".repeat(129), a))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortPasswordCannotBeRegistered() { PasswordHash.create("short") }
}
