package com.examhalls.exception;

import com.examhalls.util.Messages;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.*;

class OracleErrorTranslatorTest {

    @Test
    void applicationErrorKeepsPlsqlDetail() {
        SQLException e = new SQLException(
                "ORA-20011: Not enough exam capacity: 300 students, only 248 free seats.\nORA-06512: at \"EXAM_ADMIN.PKG_SEATING\", line 120",
                "72000", 20011);
        AppException ae = OracleErrorTranslator.translate(e);
        assertEquals(ErrorCode.INSUFFICIENT_CAPACITY, ae.code());
        assertEquals("Not enough exam capacity: 300 students, only 248 free seats.", ae.detail());
        assertTrue(ae.userMessage(Messages.ENGLISH).startsWith("There are not enough free exam seats"));
        assertTrue(ae.userMessage(Messages.ARABIC).contains("مقاعد"));
    }

    @Test
    void uniqueConstraintGetsSpecificMessage() {
        SQLException e = new SQLException("ORA-00001: unique constraint (EXAM_ADMIN.UK_ROOMS_CODE) violated", "23000", 1);
        AppException ae = OracleErrorTranslator.translate(e);
        assertEquals(ErrorCode.DUPLICATE_VALUE, ae.code());
        assertEquals("A room with this code already exists.", ae.userMessage(Messages.ENGLISH));
        assertEquals("توجد قاعة بنفس الكود.", ae.userMessage(Messages.ARABIC));
    }

    @Test
    void unknownConstraintFallsBackToGenericMessage() {
        SQLException e = new SQLException("ORA-02292: integrity constraint (EXAM_ADMIN.FK_SOMETHING_NEW) violated - child record found", "23000", 2292);
        AppException ae = OracleErrorTranslator.translate(e);
        assertEquals(ErrorCode.CHILD_RECORDS_EXIST, ae.code());
        assertEquals("This record is referenced by other data and cannot be deleted.", ae.userMessage(Messages.ENGLISH));
    }

    @Test
    void waitTimeoutMapsToRecordLocked() {
        SQLException e = new SQLException("ORA-30006: resource busy; acquire with WAIT timeout expired", "61000", 30006);
        assertEquals(ErrorCode.RECORD_LOCKED, OracleErrorTranslator.translate(e).code());
    }

    @Test
    void poolTimeoutMapsToDatabaseUnavailable() {
        SQLException e = new SQLTransientConnectionException("ExamHallsPool - Connection is not available");
        assertEquals(ErrorCode.DATABASE_UNAVAILABLE, OracleErrorTranslator.translate(e).code());
    }

    @Test
    void wrongDatabasePasswordIsALoginErrorNotAnOutage() {
        SQLException e = new SQLException("ORA-01017: invalid credential or not authorized; logon denied", "72000", 1017);
        assertEquals(ErrorCode.DATABASE_LOGIN_FAILED, OracleErrorTranslator.translate(e).code());
        assertEquals(ErrorCode.DATABASE_LOGIN_FAILED,
                OracleErrorTranslator.translate(new SQLException("ORA-28000: The account is locked.", "72000", 28000)).code());
    }

    @Test
    void wrappedSqlExceptionsAreFoundInTheCauseChain() {
        RuntimeException poolStart = new RuntimeException("Failed to initialize pool",
                new SQLException("ORA-01017: invalid credential", "72000", 1017));
        assertEquals(ErrorCode.DATABASE_LOGIN_FAILED, OracleErrorTranslator.wrap(poolStart).code());
        assertEquals(ErrorCode.UNEXPECTED, OracleErrorTranslator.wrap(new IllegalStateException("x")).code());
    }

    @Test
    void unknownOracleErrorIsUnexpected() {
        SQLException e = new SQLException("ORA-00904: \"FOO\": invalid identifier", "42000", 904);
        assertEquals(ErrorCode.UNEXPECTED, OracleErrorTranslator.translate(e).code());
    }
}
