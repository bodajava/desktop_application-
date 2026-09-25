package com.examhalls;

import com.examhalls.config.DatabaseConnection;
import com.examhalls.exception.AppException;
import com.examhalls.exception.OracleErrorTranslator;
import com.examhalls.util.Messages;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Entry point of the runnable jar.
 *
 * <p>Starting a class that extends {@link javafx.application.Application} directly from a classpath
 * jar fails with "JavaFX runtime components are missing"; delegating from here avoids that check.
 *
 * <pre>
 *   java -jar examhalls.jar              start the desktop application
 *   java -jar examhalls.jar --check-db   test the configured Oracle connection and exit
 *                                        (exit code 0 = reachable, 1 = not reachable)
 * </pre>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--check-db".equals(args[0])) {
            System.exit(checkDatabase());
        }
        MainApp.main(args);
    }

    /**
     * Used by run-app.bat / run-app.sh before the UI starts. No JavaFX and no pool: exactly one login
     * attempt with the merged configuration (file, DB_* environment variables, -D properties).
     */
    static int checkDatabase() {
        System.setProperty("CONSOLE_LOG_LEVEL", "WARN");      // keep the script output readable
        Properties cfg = DatabaseConnection.loadProperties();
        Properties login = new Properties();
        login.setProperty("user", cfg.getProperty("db.username", ""));
        login.setProperty("password", cfg.getProperty("db.password", ""));
        login.setProperty("oracle.net.CONNECT_TIMEOUT", cfg.getProperty("db.oracle.connectTimeoutMs", "4000"));
        DriverManager.setLoginTimeout(10);
        try (Connection con = DriverManager.getConnection(cfg.getProperty("db.url", ""), login)) {
            DatabaseMetaData md = con.getMetaData();
            System.out.println("[OK] Database reachable: " + md.getDatabaseProductVersion().lines().findFirst().orElse("")
                    + " | user " + md.getUserName() + " | " + md.getURL());
            return 0;
        } catch (Exception e) {
            AppException ae = OracleErrorTranslator.wrap(e);
            System.out.println("[FAILED] " + ae.userMessage(Messages.ENGLISH));
            if (ae.detail() != null) {
                System.out.println("         " + ae.detail());
            }
            return 1;
        }
    }
}
