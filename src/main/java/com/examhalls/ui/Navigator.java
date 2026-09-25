package com.examhalls.ui;

import com.examhalls.MainApp;
import com.examhalls.controller.ShellController;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.Permission;
import com.examhalls.security.RoleType;
import com.examhalls.util.Messages;
import javafx.fxml.FXMLLoader;
import javafx.geometry.NodeOrientation;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Owns the single application Scene and swaps its root: Login <-> Shell. Inside the shell,
 * pages are swapped by {@link ShellController#navigate}. All FXML is loaded with the current
 * language bundle and orientation (RTL for Arabic).
 */
public final class Navigator {

    private static Navigator instance;

    private final Stage stage;
    private final Scene scene;
    private ShellController shell;

    private Navigator(Stage stage, Scene scene) {
        this.stage = stage;
        this.scene = scene;
    }

    public static Navigator init(Stage stage, Scene scene) {
        instance = new Navigator(stage, scene);
        return instance;
    }

    public static Navigator get() {
        return instance;
    }

    public static String stylesheet() {
        return MainApp.resource("css/app.css").toExternalForm();
    }

    /** Loaded FXML root with its controller. */
    public record Loaded<C>(Parent root, C controller) {
    }

    public static <C> Loaded<C> load(String viewName) {
        FXMLLoader loader = new FXMLLoader(MainApp.resource("view/" + viewName + ".fxml"), Messages.bundle());
        try {
            Parent root = loader.load();
            applyLanguage(root);
            return new Loaded<>(root, loader.getController());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load view " + viewName, e);
        }
    }

    /**
     * RTL orientation plus an Arabic-capable font (see .rtl in app.css) for the current language,
     * and the current light/dark theme when this node becomes a window's root.
     */
    public static void applyLanguage(Parent root) {
        root.setNodeOrientation(orientation());
        if (Messages.isRightToLeft()) {
            root.getStyleClass().add("rtl");
        }
        Theme.apply(root);
    }

    public static NodeOrientation orientation() {
        return Messages.isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT;
    }

    public Stage stage() {
        return stage;
    }

    /** Returns to the sign-in screen; the caller has already ended the session (or none existed). */
    public void showLogin() {
        if (shell != null) {
            shell.dispose();
        }
        shell = null;
        scene.setRoot(load("LoginView").root());
        stage.setTitle(Messages.get("app.title"));
    }

    public void showShell(AuthenticatedUser user) {
        showShell(user, homeFor(user.role()));
    }

    /** Builds the signed-in frame and opens {@code view}; replaces (and disposes) any previous frame. */
    public void showShell(AuthenticatedUser user, View view) {
        if (shell != null) {
            shell.dispose();
        }
        Loaded<ShellController> loaded = load("MainLayout");
        shell = loaded.controller();
        scene.setRoot(loaded.root());
        stage.setTitle(Messages.get("app.title"));
        shell.start(user);
        shell.navigate(view, null);
    }

    public ShellController shell() {
        return shell;
    }

    /** Role-based landing page. */
    public static View homeFor(RoleType role) {
        if (role.has(Permission.ALLOCATE_PROCTORS)) {
            return View.EXAM_SCHEDULE;
        }
        if (role.has(Permission.VIEW_ALL_SCHEDULES)) {
            return View.DASHBOARD;
        }
        return role.has(Permission.VIEW_OWN_EXAMS) ? View.MY_EXAMS : View.MY_DUTIES;
    }
}
