package com.examhalls.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * One definition of what a username and a password may look like, shared by the login screen
 * (client-side check), {@code AuthService} and {@code UserManagementService} (server-side checks).
 *
 * <p>BCrypt only uses the first 72 bytes of a password and the library rejects longer input with an
 * exception, so every entry point bounds the UTF-8 length before hashing or verifying.
 */
public final class CredentialPolicy {

    /** 3-50 letters, digits, dot, dash or underscore: no spaces, quotes, SQL or control characters. */
    public static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,50}$");

    public static final int USERNAME_MAX_CHARS = 50;
    public static final int PASSWORD_MIN_CHARS = 8;
    public static final int PASSWORD_MAX_CHARS = 64;
    /** Upper bound accepted in the login field; anything longer cannot be a valid password. */
    public static final int LOGIN_PASSWORD_MAX_CHARS = 128;
    public static final int BCRYPT_MAX_BYTES = 72;

    private CredentialPolicy() {
    }

    public static boolean isValidUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    /** A value that could be a stored password at all (checked before any BCrypt call). */
    public static boolean isPlausiblePassword(char[] password) {
        return password != null && password.length > 0 && password.length <= LOGIN_PASSWORD_MAX_CHARS
                && utf8Length(password) <= BCRYPT_MAX_BYTES;
    }

    /** Policy for new passwords: 8-64 characters, letters and digits, at most 72 UTF-8 bytes. */
    public static boolean isStrongEnough(char[] password) {
        if (password == null || password.length < PASSWORD_MIN_CHARS || password.length > PASSWORD_MAX_CHARS
                || utf8Length(password) > BCRYPT_MAX_BYTES) {
            return false;
        }
        boolean letter = false, digit = false;
        for (char c : password) {
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        return letter && digit;
    }

    /**
     * Username as it may appear in a log line: control characters (CR/LF could forge log entries)
     * are replaced, and the length is capped.
     */
    public static String forLog(String username) {
        if (username == null) {
            return "<none>";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < username.length() && b.length() < USERNAME_MAX_CHARS; i++) {
            char c = username.charAt(i);
            b.append(Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ? '?' : c);
        }
        return username.length() > USERNAME_MAX_CHARS ? b + "…" : b.toString();
    }

    /** UTF-8 byte length without creating a String; the temporary buffer is wiped. */
    static int utf8Length(char[] chars) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        int length = bytes.remaining();
        if (bytes.hasArray()) {
            java.util.Arrays.fill(bytes.array(), (byte) 0);
        }
        return length;
    }
}
