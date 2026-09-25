package com.examhalls.controller;

import com.examhalls.MainApp;
import com.examhalls.exception.AppException;
import com.examhalls.security.AuthenticatedUser;
import com.examhalls.security.CredentialPolicy;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.ui.Theme;
import com.examhalls.util.Messages;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;

/**
 * Sign-in screen. Client-side checks (length caps while typing, username format on submit) give
 * fast feedback only; {@code AuthService} repeats every check on the server side.
 */
public class LoginController {

    /** Survives the view reload when the language is switched. */
    private static String rememberedUsername = "";
    /** Set when the previous session ended by idle timeout; shown until the next sign-in. */
    private static long expiredAfterMinutes;

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Label noticeLabel;
    @FXML private Button signInButton;
    @FXML private ProgressIndicator spinner;
    @FXML private ToggleButton englishToggle;
    @FXML private ToggleButton arabicToggle;
    @FXML private ToggleButton darkToggle;
    @FXML private ToggleButton lightToggle;

    @FXML
    private void initialize() {
        (Messages.isRightToLeft() ? arabicToggle : englishToggle).setSelected(true);
        (Theme.current() == Theme.LIGHT ? lightToggle : darkToggle).setSelected(true);
        usernameField.setTextFormatter(maxLength(CredentialPolicy.USERNAME_MAX_CHARS));
        passwordField.setTextFormatter(maxLength(CredentialPolicy.LOGIN_PASSWORD_MAX_CHARS));
        usernameField.setText(rememberedUsername);
        if (expiredAfterMinutes > 0) {
            noticeLabel.setText(Messages.get("login.sessionExpired", expiredAfterMinutes));
            noticeLabel.setVisible(true);
            noticeLabel.setManaged(true);
        }
        Platform.runLater(usernameField::requestFocus);
        usernameField.textProperty().addListener((o, a, b) -> clearError());
        passwordField.textProperty().addListener((o, a, b) -> clearError());
    }

    @FXML
    private void onSignIn() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().strip();
        if (username.isEmpty() || passwordField.getText().isEmpty()) {
            showError(Messages.get("login.required"));
            return;
        }
        if (!CredentialPolicy.isValidUsername(username)) {
            showError(Messages.get("login.invalidUsername"));
            return;
        }
        char[] password = passwordField.getText().toCharArray();
        setBusy(true);
        FxAsync.run(
                () -> ServiceRegistry.get().authService().login(username, password),
                this::onAuthenticated,
                this::onFailed,
                () -> setBusy(false));
    }

    private void onAuthenticated(AuthenticatedUser user) {
        passwordField.clear();
        rememberedUsername = "";
        expiredAfterMinutes = 0;
        Navigator.get().showShell(user);
    }

    private void onFailed(AppException e) {
        passwordField.clear();
        passwordField.requestFocus();
        showError(e.userMessage());
    }

    /** Called by the shell when it signs the user out for inactivity. */
    public static void noticeSessionExpired(long minutes) {
        expiredAfterMinutes = minutes;
    }

    /** Caps the field at {@code max} characters; a longer paste is cut to fit. */
    private static TextFormatter<String> maxLength(int max) {
        return new TextFormatter<>(change -> {
            int excess = change.getControlNewText().length() - max;
            if (excess <= 0) {
                return change;
            }
            String added = change.getText();
            if (excess > added.length()) {
                return null;
            }
            change.setText(added.substring(0, added.length() - excess));
            return change;
        });
    }

    @FXML
    private void onTheme() {
        boolean light = lightToggle.isSelected();
        if (!light && !darkToggle.isSelected()) {           // keep one toggle selected
            darkToggle.setSelected(true);
        }
        Theme.set(light ? Theme.LIGHT : Theme.DARK);
    }

    @FXML
    private void onLanguage() {
        boolean arabic = arabicToggle.isSelected();
        if (!arabic && !englishToggle.isSelected()) {       // keep one toggle selected
            englishToggle.setSelected(true);
        }
        MainApp.setLanguage(arabic ? Messages.ARABIC : Messages.ENGLISH);
        rememberedUsername = usernameField.getText();
        Navigator.get().showLogin();
    }

    private void setBusy(boolean busy) {
        signInButton.setDisable(busy);
        usernameField.setDisable(busy);
        passwordField.setDisable(busy);
        spinner.setVisible(busy);
        spinner.setManaged(busy);
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void clearError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
