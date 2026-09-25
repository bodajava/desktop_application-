package com.examhalls.exception;

import com.examhalls.util.Messages;

import java.util.Locale;
import java.util.Objects;

/**
 * The single unchecked exception type services throw to the UI.
 * <ul>
 *   <li>{@link #code()} — machine-readable category</li>
 *   <li>{@link #userMessage(Locale)} — translated, human-readable sentence (English / Arabic)</li>
 *   <li>{@link #detail()} — technical detail (e.g. the PL/SQL message text with numbers),
 *       useful as a secondary line or tooltip; may be {@code null}</li>
 * </ul>
 */
public class AppException extends RuntimeException {

    private final ErrorCode code;
    private final String messageKey;
    private final String detail;
    private final Object[] args;

    public AppException(ErrorCode code, String detail, Throwable cause) {
        this(code, code.messageKey(), detail, cause);
    }

    public AppException(ErrorCode code, String detail) {
        this(code, detail, null);
    }

    public AppException(ErrorCode code) {
        this(code, null, null);
    }

    /** Uses a more specific message key (e.g. a constraint-specific duplicate message). */
    public AppException(ErrorCode code, String messageKey, String detail, Throwable cause, Object... args) {
        super(code + (detail != null ? ": " + detail : ""), cause);
        this.code = Objects.requireNonNull(code);
        this.messageKey = messageKey;
        this.detail = detail;
        this.args = args;
    }

    public ErrorCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public String userMessage(Locale locale) {
        return Messages.get(locale, messageKey, code.messageKey(), args);
    }

    public String userMessage() {
        return userMessage(Messages.currentLocale());
    }
}
