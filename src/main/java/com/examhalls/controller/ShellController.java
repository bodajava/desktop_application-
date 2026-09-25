package com.examhalls.controller;

import com.examhalls.config.AppSettings;
import com.examhalls.config.DatabaseConnection;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.IdleTimer;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.util.Formats;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.ui.View;
import com.examhalls.util.Messages;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.event.EventType;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.InputEvent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The signed-in application frame: role-filtered sidebar, page header, content area, status bar.
 * Also owns the session idle timeout: keyboard, mouse and scroll input in any window of the app
 * counts as activity; after {@code security.session.idleTimeoutMinutes} without any, the session
 * is ended exactly like a manual sign-out.
 */
public class ShellController {

    private static final Logger log = LoggerFactory.getLogger(ShellController.class);
    private static final Duration IDLE_CHECK_EVERY = Duration.seconds(15);
    private static final List<EventType<? extends InputEvent>> ACTIVITY = List.of(
            KeyEvent.KEY_PRESSED, MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_MOVED, ScrollEvent.SCROLL);

    @FXML private VBox navBox;
    @FXML private Label pageTitle;
    @FXML private Label pageSubtitle;
    @FXML private StackPane contentArea;
    @FXML private Label userNameLabel;
    @FXML private Label userRoleLabel;
    @FXML private Label toastLabel;
    @FXML private Label dbStatusDot;
    @FXML private Label dbStatusLabel;

    private final Map<View, Button> navButtons = new EnumMap<>(View.class);
    private AuthenticatedUser user;
    private SequentialTransition toastAnimation;

    private IdleTimer idleTimer;
    private Timeline idleCheck;
    private final EventHandler<InputEvent> onActivity = e -> idleTimer.touch();
    private final ListChangeListener<Window> onWindowsChanged = c -> {
        while (c.next()) {
            c.getAddedSubList().forEach(this::watch);
        }
    };

    public void start(AuthenticatedUser user) {
        this.user = user;
        userNameLabel.setText(user.fullName());
        userRoleLabel.setText(user.role().displayName(Messages.currentLocale()));
        pageSubtitle.setText(Formats.longDate(LocalDate.now()));

        View.Section section = null;
        for (View view : View.values()) {
            if (!user.can(view.permission())) {
                continue;
            }
            if (view.section() != section) {
                section = view.section();
                Label heading = new Label(Messages.get("nav.section." + section.name()));
                heading.getStyleClass().add("nav-section");
                navBox.getChildren().add(heading);
            }
            Button b = new Button(Messages.get(view.titleKey()));
            b.getStyleClass().add("nav-button");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> navigate(view, null));
            navButtons.put(view, b);
            navBox.getChildren().add(b);
        }
        refreshDbStatus();
        startIdleTimeout();
    }

    private void startIdleTimeout() {
        int minutes = AppSettings.getInt("security.session.idleTimeoutMinutes", 15);
        idleTimer = new IdleTimer(java.time.Duration.ofMinutes(minutes), Clock.systemUTC());
        if (!idleTimer.isEnabled()) {
            return;
        }
        Window.getWindows().forEach(this::watch);
        Window.getWindows().addListener(onWindowsChanged);
        idleCheck = new Timeline(new KeyFrame(IDLE_CHECK_EVERY, e -> {
            if (idleTimer.isExpired()) {
                endSessionForInactivity();
            }
        }));
        idleCheck.setCycleCount(Animation.INDEFINITE);
        idleCheck.play();
    }

    private void watch(Window window) {
        ACTIVITY.forEach(type -> window.addEventFilter(type, onActivity));
    }

    private void unwatch(Window window) {
        ACTIVITY.forEach(type -> window.removeEventFilter(type, onActivity));
    }

    /**
     * Ends the session after the idle timeout: closes every dialog and popup, clears the session
     * and returns to the sign-in screen with an explanation.
     */
    public void endSessionForInactivity() {
        long minutes = idleTimer == null ? 0 : idleTimer.timeout().toMinutes();
        log.info("Session ended after {} minutes of inactivity", minutes);
        Window main = Navigator.get().stage();
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (w != main) {
                w.hide();
            }
        }
        ServiceRegistry.get().authService().logout();
        LoginController.noticeSessionExpired(minutes);
        Navigator.get().showLogin();
    }

    /** Stops the idle timer and detaches listeners; called by {@link Navigator#showLogin()}. */
    public void dispose() {
        if (idleCheck != null) {
            idleCheck.stop();
            idleCheck = null;
        }
        Window.getWindows().removeListener(onWindowsChanged);
        if (idleTimer != null && idleTimer.isEnabled()) {
            new ArrayList<>(Window.getWindows()).forEach(this::unwatch);
        }
        if (toastAnimation != null) {
            toastAnimation.stop();
        }
        user = null;
    }

    /**
     * Shows a page. {@code init} receives the page controller right after loading (e.g. to open a
     * specific exam). Pages the role may not open are ignored.
     */
    @SuppressWarnings("unchecked")
    public <C> void navigate(View view, Consumer<C> init) {
        if (user == null || !user.can(view.permission())) {
            return;
        }
        Navigator.Loaded<C> loaded = Navigator.load(view.fxml());
        contentArea.getChildren().setAll(loaded.root());
        navButtons.values().forEach(b -> b.getStyleClass().remove("active"));
        Button active = navButtons.get(view);
        if (active != null) {
            active.getStyleClass().add("active");
        }
        pageTitle.setText(Messages.get(view.titleKey()));
        if (init != null) {
            init.accept(loaded.controller());
        }
    }

    /** Short, non-blocking confirmation in the top bar. */
    public void toast(String message) {
        if (toastAnimation != null) {
            toastAnimation.stop();
        }
        toastLabel.setText(message);
        toastLabel.setOpacity(1);
        toastLabel.setVisible(true);
        FadeTransition fade = new FadeTransition(Duration.millis(600), toastLabel);
        fade.setToValue(0);
        toastAnimation = new SequentialTransition(new PauseTransition(Duration.seconds(4)), fade);
        toastAnimation.setOnFinished(e -> toastLabel.setVisible(false));
        toastAnimation.play();
    }

    public void refreshDbStatus() {
        setDbStatus(Messages.get("status.checking"), "status-idle");
        FxAsync.run(() -> DatabaseConnection.getInstance().isHealthy(),
                ok -> setDbStatus(Messages.get(ok ? "status.connected" : "status.disconnected"),
                        ok ? "status-ok" : "status-error"),
                e -> setDbStatus(e.userMessage(), "status-error"));
    }

    @FXML
    private void onSignOut() {
        ServiceRegistry.get().authService().logout();
        Navigator.get().showLogin();
    }

    private void setDbStatus(String text, String styleClass) {
        dbStatusLabel.setText(text);
        dbStatusDot.getStyleClass().removeAll("status-idle", "status-ok", "status-error");
        dbStatusDot.getStyleClass().add(styleClass);
    }
}
