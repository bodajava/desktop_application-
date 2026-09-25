package com.examhalls.exception;

import com.examhalls.security.RoleType;
import com.examhalls.util.Messages;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Every key must exist in English AND Arabic, so no user ever sees a raw key or English fallback in Arabic mode. */
class MessagesCompletenessTest {

    private static Properties load(String file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = MessagesCompletenessTest.class.getResourceAsStream("/com/examhalls/i18n/" + file)) {
            assertNotNull(in, file + " missing");
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void englishAndArabicHaveTheSameKeys() throws IOException {
        Properties en = load("messages.properties");
        Properties ar = load("messages_ar.properties");
        assertEquals(en.stringPropertyNames(), ar.stringPropertyNames());
    }

    @Test
    void everyErrorCodeAndRoleIsTranslated() throws IOException {
        Properties en = load("messages.properties");
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(en.containsKey(code.messageKey()), "missing " + code.messageKey());
        }
        for (RoleType role : RoleType.values()) {
            assertTrue(en.containsKey("role." + role.name()), "missing role." + role.name());
        }
    }

    @Test
    void arabicTextIsReallyArabic() {
        String text = Messages.get(Messages.ARABIC, ErrorCode.ACCESS_DENIED.messageKey(), null);
        assertTrue(text.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC), text);
        assertEquals("Control Officer", RoleType.CONTROL_OFFICER.displayName(Locale.ENGLISH));
        assertEquals("مسؤول الكنترول", RoleType.CONTROL_OFFICER.displayName(Messages.ARABIC));
    }

    @Test
    void formatsArguments() {
        AppException locked = new AppException(ErrorCode.ACCOUNT_LOCKED, ErrorCode.ACCOUNT_LOCKED.messageKey(), null, null, 5);
        assertEquals("Too many failed attempts. Try again in 5 minute(s).", locked.userMessage(Messages.ENGLISH));
    }
}
