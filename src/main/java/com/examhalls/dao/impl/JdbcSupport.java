package com.examhalls.dao.impl;

import com.examhalls.config.TransactionManager;
import com.examhalls.exception.AppException;
import com.examhalls.exception.ErrorCode;
import com.examhalls.exception.OracleErrorTranslator;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Minimal JDBC template shared by all DAOs: PreparedStatement only, connections via
 * {@link TransactionManager} (so DAO calls join a service transaction when one is open),
 * and every SQLException translated to an {@link AppException}.
 */
abstract class JdbcSupport {

    @FunctionalInterface
    protected interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    protected interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    protected static final Binder NO_PARAMS = ps -> { };

    protected final TransactionManager tx;

    protected JdbcSupport(TransactionManager tx) {
        this.tx = tx;
    }

    protected <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        try (TransactionManager.ConnectionHandle h = tx.connection();
             PreparedStatement ps = h.get().prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw OracleErrorTranslator.translate(e);
        }
    }

    protected <T> Optional<T> queryOne(String sql, Binder binder, RowMapper<T> mapper) {
        List<T> rows = query(sql, binder, mapper);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    protected int update(String sql, Binder binder) {
        try (TransactionManager.ConnectionHandle h = tx.connection();
             PreparedStatement ps = h.get().prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw OracleErrorTranslator.translate(e);
        }
    }

    /** Like {@link #update} but fails with NOT_FOUND when no row was affected. */
    protected void updateExactlyOne(String sql, String entity, long id, Binder binder) {
        if (update(sql, binder) == 0) {
            throw new AppException(ErrorCode.NOT_FOUND, entity + " " + id + " does not exist or is archived");
        }
    }

    /** INSERT returning the IDENTITY value of {@code idColumn}. */
    protected long insert(String sql, String idColumn, Binder binder) {
        try (TransactionManager.ConnectionHandle h = tx.connection();
             PreparedStatement ps = h.get().prepareStatement(sql, new String[]{idColumn})) {
            binder.bind(ps);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new AppException(ErrorCode.UNEXPECTED, "No generated key returned for " + idColumn);
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw OracleErrorTranslator.translate(e);
        }
    }

    // ------------------------------------------------------------------ binding helpers

    protected static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setLong(index, value);
        }
    }

    protected static void setDate(PreparedStatement ps, int index, LocalDate value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setDate(index, java.sql.Date.valueOf(value));
        }
    }

    protected static String flag(boolean value) {
        return value ? "Y" : "N";
    }

    // ------------------------------------------------------------------ reading helpers

    protected static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    protected static LocalDateTime getDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    protected static LocalDate getLocalDate(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime().toLocalDate();
    }

    protected static BigDecimal getDecimal(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    protected static boolean getFlag(ResultSet rs, String column) throws SQLException {
        return "Y".equals(rs.getString(column));
    }
}
