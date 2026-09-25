package com.examhalls;

import com.examhalls.config.DatabaseConnection;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Messages;
import javafx.application.Application;
import javafx.application.HostServices;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Locale;
import java.util.Objects;
import java.util.prefs.Preferences;

public class MainApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);
    private static final String PREF_LANGUAGE = "language";
    private static HostServices hostServices;

    @Override
    public void start(Stage stage) {
        Thread.setDefaultUncaughtExceptionHandler(
                (t, e) -> log.error("Uncaught exception on thread {}", t.getName(), e));

        Messages.setLocale(savedLanguage());
        hostServices = getHostServices();

        Scene scene = new Scene(new StackPane(), 1280, 800);
        scene.getStylesheets().add(resource("css/app.css").toExternalForm());

        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        stage.setScene(scene);
        Navigator.init(stage, scene).showLogin();
        stage.show();
        log.info("UI started ({})", Messages.currentLocale());
    }

    @Override
    public void stop() {
        DatabaseConnection.shutdown();
        log.info("Application stopped");
    }

    /** Switches and remembers the UI language (per OS user). */
    public static void setLanguage(Locale locale) {
        Messages.setLocale(locale);
        try {
            Preferences.userNodeForPackage(MainApp.class).put(PREF_LANGUAGE, locale.getLanguage());
        } catch (RuntimeException e) {
            log.debug("Could not persist language preference", e);
        }
    }

    private static Locale savedLanguage() {
        try {
            String lang = Preferences.userNodeForPackage(MainApp.class).get(PREF_LANGUAGE, "en");
            return "ar".equals(lang) ? Messages.ARABIC : Messages.ENGLISH;
        } catch (RuntimeException e) {
            return Messages.ENGLISH;
        }
    }

    /** Opens a file with the OS default application (PDF viewer, Excel...). */
    public static void openFile(java.nio.file.Path file) {
        if (hostServices != null) {
            hostServices.showDocument(file.toUri().toString());
        }
    }

    /** Resolves a resource relative to the {@code com/examhalls} resource folder. */
    public static URL resource(String relativePath) {
        return Objects.requireNonNull(MainApp.class.getResource(relativePath),
                () -> "Resource not found: com/examhalls/" + relativePath);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
