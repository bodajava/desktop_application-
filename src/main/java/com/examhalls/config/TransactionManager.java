package com.examhalls.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Thread-bound transactions over the HikariCP pool.
 *
 * <p>DAOs never open connections directly; they call {@link #connection()}:
 * <ul>
 *   <li>inside {@link #inTransaction}: they share the transaction's connection (not closed by the DAO);</li>
 *   <li>outside: they get a fresh auto-commit connection from the pool, returned on close.</li>
 * </ul>
 * This lets a service run several DAO calls / stored procedures atomically, and lets tests or
 * "preview" features run real work and discard it with {@link #runAndRollback}.
 */
public final class TransactionManager {

    @FunctionalInterface
    public interface SqlWork<T> {
        T execute() throws SQLException;
    }

    private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();

    private final DataSource dataSource;

    public TransactionManager(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Uses the application-wide pool. */
    public static TransactionManager fromPool() {
        return new TransactionManager(DatabaseConnection.getInstance().getDataSource());
    }

    /** Borrow a connection; always use in try-with-resources. */
    public ConnectionHandle connection() throws SQLException {
        Connection tx = CURRENT.get();
        return tx != null ? new ConnectionHandle(tx, false) : new ConnectionHandle(dataSource.getConnection(), true);
    }

    /** Runs {@code work} in one transaction: commit on success, rollback on any exception. Nested calls join. */
    public <T> T inTransaction(SqlWork<T> work) throws SQLException {
        return run(work, false);
    }

    /** Runs {@code work} in one transaction and ALWAYS rolls back (tests, dry-run previews). */
    public <T> T runAndRollback(SqlWork<T> work) throws SQLException {
        return run(work, true);
    }

    public static boolean inTransaction() {
        return CURRENT.get() != null;
    }

    private <T> T run(SqlWork<T> work, boolean rollbackOnly) throws SQLException {
        if (CURRENT.get() != null) {
            return work.execute();              // join the outer transaction
        }
        try (Connection con = dataSource.getConnection()) {
            boolean previousAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            CURRENT.set(con);
            try {
                T result = work.execute();
                if (rollbackOnly) {
                    con.rollback();
                } else {
                    con.commit();
                }
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                try {
                    con.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                CURRENT.remove();
                con.setAutoCommit(previousAutoCommit);
            }
        }
    }

    /** Wraps a connection so DAOs can always close it; only pool-owned connections are really closed. */
    public static final class ConnectionHandle implements AutoCloseable {
        private final Connection connection;
        private final boolean owned;

        private ConnectionHandle(Connection connection, boolean owned) {
            this.connection = connection;
            this.owned = owned;
        }

        public Connection get() {
            return connection;
        }

        @Override
        public void close() throws SQLException {
            if (owned) {
                connection.close();
            }
        }
    }
}
