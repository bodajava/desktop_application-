package com.examhalls.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.util.Arrays;

/** BCrypt hashing (2a variant, compatible with the seed data). */
public final class PasswordHasher {

    public static final int DEFAULT_COST = 12;

    private final int cost;

    public PasswordHasher() {
        this(DEFAULT_COST);
    }

    public PasswordHasher(int cost) {
        if (cost < 10 || cost > 16) {
            throw new IllegalArgumentException("BCrypt cost should be between 10 and 16, was " + cost);
        }
        this.cost = cost;
    }

    public String hash(char[] password) {
        return BCrypt.withDefaults().hashToString(cost, password);
    }

    /** False (never an exception) for missing or over-long input: BCrypt rejects more than 72 bytes. */
    public boolean verify(char[] password, String hash) {
        if (hash == null || !CredentialPolicy.isPlausiblePassword(password)) {
            return false;
        }
        return BCrypt.verifyer().verify(password, hash).verified;
    }

    /** See {@link CredentialPolicy#isStrongEnough}: 8-64 characters, letters and digits, at most 72 bytes. */
    public static boolean isStrongEnough(char[] password) {
        return CredentialPolicy.isStrongEnough(password);
    }

    public static void wipe(char[] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }
}
