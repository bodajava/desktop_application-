package com.examhalls.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CredentialPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"admin", "control", "t.ahmed-01", "a_b", "ABC"})
    void acceptsWellFormedUsernames(String username) {
        assertTrue(CredentialPolicy.isValidUsername(username));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ab", " admin", "admin ", "ad min", "   ", "admin'--", "' OR '1'='1",
            "admin; DROP TABLE users", "admin\"", "a/**/b", "أحمد", "admin\n", "admin\u0000", "admin‏",
            "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijX"})
    void rejectsMalformedUsernames(String username) {
        assertFalse(CredentialPolicy.isValidUsername(username));
    }

    @Test
    void plausiblePasswordStaysWithinBcryptLimit() {
        assertFalse(CredentialPolicy.isPlausiblePassword(null));
        assertFalse(CredentialPolicy.isPlausiblePassword(new char[0]));
        assertTrue(CredentialPolicy.isPlausiblePassword("x".toCharArray()));
        assertTrue(CredentialPolicy.isPlausiblePassword("a".repeat(72).toCharArray()));
        assertFalse(CredentialPolicy.isPlausiblePassword("a".repeat(73).toCharArray()));
        assertTrue(CredentialPolicy.isPlausiblePassword("س".repeat(36).toCharArray()));   // 72 bytes
        assertFalse(CredentialPolicy.isPlausiblePassword("س".repeat(37).toCharArray()));  // 74 bytes
    }

    @Test
    void strengthPolicy() {
        assertTrue(CredentialPolicy.isStrongEnough("Admin@2026".toCharArray()));
        assertTrue(CredentialPolicy.isStrongEnough("كلمةسر2026".toCharArray()));
        assertFalse(CredentialPolicy.isStrongEnough("short1".toCharArray()));
        assertFalse(CredentialPolicy.isStrongEnough("lettersonly".toCharArray()));
        assertFalse(CredentialPolicy.isStrongEnough("12345678".toCharArray()));
        assertFalse(CredentialPolicy.isStrongEnough(("a1".repeat(33)).toCharArray()));    // 66 characters
        assertFalse(CredentialPolicy.isStrongEnough(("س".repeat(40) + "1").toCharArray())); // 81 bytes
        assertFalse(CredentialPolicy.isStrongEnough(null));
    }

    @Test
    void logSanitiserBlocksForgedLines() {
        assertEquals("evil??INFO forged", CredentialPolicy.forLog("evil\r\nINFO forged"));
        assertEquals("<none>", CredentialPolicy.forLog(null));
        assertEquals("x".repeat(50) + "…", CredentialPolicy.forLog("x".repeat(500)));
        assertEquals("admin", CredentialPolicy.forLog("admin"));
    }
}
