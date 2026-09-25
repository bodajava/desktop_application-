package com.examhalls.exception;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts JDBC / Oracle exceptions into {@link AppException}s with a stable {@link ErrorCode}.
 *
 * <ul>
 *   <li>ORA-20xxx from RAISE_APPLICATION_ERROR: the code comes from {@code getErrorCode()} and the
 *       PL/SQL message text (with its numbers) becomes {@link AppException#detail()}.</li>
 *   <li>Constraint errors (ORA-00001/02290/02291/02292/01400): the constraint name is extracted so a
 *       constraint-specific message ({@code constraint.UK_ROOMS_CODE}) can be shown when one exists.</li>
 *   <li>Connection problems (pool timeout, network): {@link ErrorCode#DATABASE_UNAVAILABLE}.</li>
 * </ul>
 */
public final class OracleErrorTranslator {

    // "ORA-00001: unique constraint (EXAM_ADMIN.UK_ROOMS_CODE) violated"
    private static final Pattern CONSTRAINT = Pattern.compile("\\(\\s*\"?[A-Z0-9_$#]+\"?\\.\"?([A-Z0-9_$#]+)\"?\\s*\\)");
    // First line of "ORA-20011: Not enough exam capacity: ...\nORA-06512: at ..."
    private static final Pattern ORA_TEXT = Pattern.compile("ORA-\\d{5}:\\s*(.*)");

    private OracleErrorTranslator() {
    }

    public static AppException translate(SQLException e) {
        if (isLoginError(e.getErrorCode())) {
            return new AppException(ErrorCode.DATABASE_LOGIN_FAILED, firstLine(e.getMessage()), e);
        }
        if (e instanceof SQLTransientConnectionException || e instanceof SQLRecoverableException
                || isConnectionError(e.getErrorCode())) {
            return new AppException(ErrorCode.DATABASE_UNAVAILABLE, firstLine(e.getMessage()), e);
        }

        ErrorCode code = ErrorCode.fromOracleCode(e.getErrorCode()).orElse(ErrorCode.UNEXPECTED);
        String detail = oracleText(e.getMessage());

        switch (code) {
            case DUPLICATE_VALUE, CHECK_VIOLATION, PARENT_NOT_FOUND, CHILD_RECORDS_EXIST, NOT_NULL_VIOLATION -> {
                String constraint = constraintName(e.getMessage());
                if (constraint != null) {
                    // A foreign key fails in two opposite directions, so child-side errors
                    // (ORA-02292, "still referenced") have their own keys: constraint.child.FK_...
                    String prefix = code == ErrorCode.CHILD_RECORDS_EXIST ? "constraint.child." : "constraint.";
                    return new AppException(code, prefix + constraint, detail, e);
                }
                return new AppException(code, detail, e);
            }
            default -> {
                return new AppException(code, detail, e);
            }
        }
    }

    /**
     * Rethrows AppExceptions untouched, translates SQLExceptions, wraps anything else. Walks the
     * cause chain first, because pool start-up failures arrive wrapped (PoolInitializationException
     * -> SQLException ORA-01017 ...).
     */
    public static AppException wrap(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof AppException ae) {
                return ae;
            }
            if (c instanceof SQLException se) {
                return translate(se);
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return new AppException(ErrorCode.UNEXPECTED, t.getMessage(), t);
    }

    static String constraintName(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = CONSTRAINT.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    static String oracleText(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = ORA_TEXT.matcher(message);
        return m.find() ? m.group(1).trim() : firstLine(message);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return null;
        }
        int nl = message.indexOf('\n');
        return (nl >= 0 ? message.substring(0, nl) : message).trim();
    }

    private static boolean isConnectionError(int code) {
        // 17002 IO error, 17008 closed connection, 12514/12541/12505 listener, 1033/1034 DB not open
        return code == 17002 || code == 17008 || code == 12514 || code == 12541 || code == 12505
                || code == 1033 || code == 1034;
    }

    /** 1017 wrong username/password, 28000 account locked, 28001 password expired. */
    private static boolean isLoginError(int code) {
        return code == 1017 || code == 28000 || code == 28001;
    }
}
