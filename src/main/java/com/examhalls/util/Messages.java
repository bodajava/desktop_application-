package com.examhalls.util;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Localised UI / error text from {@code com/examhalls/i18n/messages[_ar].properties} (UTF-8).
 * The current language defaults to English and can be switched at runtime ({@link #setLocale}).
 */
public final class Messages {

    public static final Locale ENGLISH = Locale.ENGLISH;
    // Arabic text with Western digits (matches codes, seat numbers and times shown in tables)
    public static final Locale ARABIC = Locale.forLanguageTag("ar-u-nu-latn");

    private static final String BUNDLE = "com.examhalls.i18n.messages";
    private static volatile Locale current = ENGLISH;

    private Messages() {
    }

    public static Locale currentLocale() {
        return current;
    }

    public static void setLocale(Locale locale) {
        current = locale;
    }

    /** The UI bundle for FXMLLoader ({@code %key} references in FXML). */
    public static ResourceBundle bundle() {
        return ResourceBundle.getBundle(BUNDLE, current,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    public static boolean isRightToLeft() {
        return "ar".equals(current.getLanguage());
    }

    public static String get(String key, Object... args) {
        return get(current, key, null, args);
    }

    /** Looks up {@code key}, falling back to {@code fallbackKey}, then to the key itself. */
    public static String get(Locale locale, String key, String fallbackKey, Object... args) {
        ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        String pattern = lookup(bundle, key);
        if (pattern == null && fallbackKey != null) {
            pattern = lookup(bundle, fallbackKey);
        }
        if (pattern == null) {
            return key;
        }
        return args == null || args.length == 0 ? pattern : new MessageFormat(pattern, locale).format(args);
    }

    private static String lookup(ResourceBundle bundle, String key) {
        try {
            return key != null && bundle.containsKey(key) ? bundle.getString(key) : null;
        } catch (MissingResourceException e) {
            return null;
        }
    }
}
