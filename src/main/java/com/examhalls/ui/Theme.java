package com.examhalls.ui;

import com.examhalls.MainApp;
import javafx.collections.ListChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.prefs.Preferences;

/**
 * Light / dark appearance of the "Graphite Shell" design system. Dark is the system's native
 * look and the default; the choice is remembered per OS user, like the language.
 *
 * <p>Only colour tokens differ between the themes: {@code app.css} defines them on {@code .root}
 * (dark) and redefines them on {@code .root.theme-light}. Switching therefore just toggles one
 * style class on the root of every open window, including dialogs and popups; nothing is reloaded.
 */
public enum Theme {
    DARK, LIGHT;

    private static final Logger log = LoggerFactory.getLogger(Theme.class);
    private static final String PREF_THEME = "theme";
    private static final String LIGHT_CLASS = "theme-light";

    private static Theme current = saved();

    public static Theme current() {
        return current;
    }

    public Theme other() {
        return this == DARK ? LIGHT : DARK;
    }

    /** Applies and remembers the theme, restyling every open window immediately. */
    public static void set(Theme theme) {
        current = theme;
        try {
            Preferences.userNodeForPackage(MainApp.class).put(PREF_THEME, theme.name().toLowerCase());
        } catch (RuntimeException e) {
            log.debug("Could not persist theme preference", e);
        }
        new ArrayList<>(Window.getWindows()).forEach(Theme::applyTo);
    }

    /** Keeps windows opened later (dialogs, tooltips, combo and date popups) in the current theme. */
    public static void install() {
        Window.getWindows().addListener((ListChangeListener<Window>) c -> {
            while (c.next()) {
                c.getAddedSubList().forEach(Theme::applyTo);
            }
        });
    }

    /** Marks a scene root with the current theme. */
    public static void apply(Parent root) {
        if (root == null) {
            return;
        }
        root.getStyleClass().remove(LIGHT_CLASS);
        if (current == LIGHT) {
            root.getStyleClass().add(LIGHT_CLASS);
        }
    }

    private static void applyTo(Window window) {
        Scene scene = window.getScene();
        if (scene != null) {
            apply(scene.getRoot());
        }
    }

    private static Theme saved() {
        try {
            String value = Preferences.userNodeForPackage(MainApp.class).get(PREF_THEME, "dark");
            return "light".equals(value) ? LIGHT : DARK;
        } catch (RuntimeException e) {
            return DARK;
        }
    }
}
