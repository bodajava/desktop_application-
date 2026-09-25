package com.examhalls.config;

import java.math.BigDecimal;
import java.util.Properties;

/** Read-only access to non-database settings from the same merged configuration as the pool. */
public final class AppSettings {

    private static volatile Properties props;

    private AppSettings() {
    }

    public static String get(String key, String defaultValue) {
        String v = properties().getProperty(key);
        return v == null || v.isBlank() ? defaultValue : v.trim();
    }

    /** Integer setting; a missing, malformed or negative value falls back to {@code defaultValue}. */
    public static int getInt(String key, int defaultValue) {
        try {
            int v = Integer.parseInt(get(key, Integer.toString(defaultValue)));
            return v < 0 ? defaultValue : v;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        try {
            return new BigDecimal(get(key, defaultValue.toPlainString()));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Properties properties() {
        Properties local = props;
        if (local == null) {
            synchronized (AppSettings.class) {
                if (props == null) {
                    props = DatabaseConnection.loadProperties();
                }
                local = props;
            }
        }
        return local;
    }
}
