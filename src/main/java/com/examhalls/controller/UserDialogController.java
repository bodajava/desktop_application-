package com.examhalls.controller;

import com.examhalls.exception.AppException;
import com.examhalls.model.Teacher;
import com.examhalls.model.UserAccount;
import com.examhalls.model.UserForm;
import com.examhalls.security.RoleType;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.service.UserManagementService;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Messages;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Create user / edit user / reset password (one dialog, three modes). */
public class UserDialogController {

    public enum Mode { CREATE, EDIT, PASSWORD }

    /** Teacher picker entry; {@code teacher == null} = not linked. */
    private record TeacherChoice(Teacher teacher) {
    }

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private VBox accountBox;
    @FXML private VBox passwordBox;
    @FXML private TextField usernameField;
    @FXML private ComboBox<RoleType> roleCombo;
    @FXML private Label teacherLabel;
    @FXML private ComboBox<TeacherChoice> teacherCombo;
    @FXML private TextField fullNameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmField;
    @FXML private Label errorLabel;
    @FXML private Button saveButton;
    @FXML private BusyOverlay busy;

    private final UserManagementService users = ServiceRegistry.get().userManagementService();
    private Stage stage;
    private Mode mode;
    private UserAccount existing;
    private boolean saved;

    /** @return true if something was saved */
    public static boolean show(Window owner, Mode mode, UserAccount existing, List<Teacher> teachers) {
        Navigator.Loaded<UserDialogController> loaded = Navigator.load("UserDialog");
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        Scene scene = new Scene(loaded.root());
        scene.getStylesheets().add(Navigator.stylesheet());
        stage.setScene(scene);
        stage.setResizable(false);
        UserDialogController c = loaded.controller();
        c.init(stage, mode, existing, teachers);
        stage.showAndWait();
        return c.saved;
    }

    private void init(Stage stage, Mode mode, UserAccount existing, List<Teacher> teachers) {
        this.stage = stage;
        this.mode = mode;
        this.existing = existing;

        roleCombo.setItems(FXCollections.observableArrayList(RoleType.values()));
        roleCombo.setConverter(converter(r -> r.displayName(Messages.currentLocale())));
        List<TeacherChoice> choices = new ArrayList<>();
        choices.add(new TeacherChoice(null));
        teachers.forEach(t -> choices.add(new TeacherChoice(t)));
        teacherCombo.setItems(FXCollections.observableArrayList(choices));
        teacherCombo.setConverter(converter(c -> c.teacher() == null ? Messages.get("users.noTeacher")
                : c.teacher().fullName() + " (" + c.teacher().teacherCode() + ")"));
        teacherCombo.valueProperty().addListener((o, a, b) -> {
            // Prefill the display name from the teacher record when it is still empty / unchanged
            if (b != null && b.teacher() != null && (fullNameField.getText().isBlank()
                    || (a != null && a.teacher() != null && fullNameField.getText().equals(a.teacher().fullName())))) {
                fullNameField.setText(b.teacher().fullName());
            }
        });
        roleCombo.valueProperty().addListener((o, a, b) -> teacherLabel.setText(Messages.get(
                b == RoleType.TEACHER ? "users.teacherRequired" : "users.col.teacher")));

        String key = switch (mode) {
            case CREATE -> "users.dialog.create";
            case EDIT -> "users.dialog.edit";
            case PASSWORD -> "users.dialog.password";
        };
        titleLabel.setText(Messages.get(key));
        stage.setTitle(titleLabel.getText());
        subtitleLabel.setText(existing == null ? Messages.get("users.dialog.createHint")
                : existing.fullName() + " · " + existing.username());
        show(accountBox, mode != Mode.PASSWORD);
        show(passwordBox, mode != Mode.EDIT);

        if (existing != null) {
            usernameField.setText(existing.username());
            fullNameField.setText(existing.fullName());
            roleCombo.setValue(existing.role());
            choices.stream().filter(c -> c.teacher() != null && c.teacher().teacherId().equals(existing.teacherId()))
                    .findFirst().ifPresentOrElse(teacherCombo::setValue, () -> teacherCombo.getSelectionModel().selectFirst());
        } else {
            roleCombo.setValue(RoleType.TEACHER);
            teacherCombo.getSelectionModel().selectFirst();
        }
    }

    @FXML
    private void onSave() {
        hideError();
        char[] password = mode == Mode.EDIT ? null : passwordField.getText().toCharArray();
        if (password != null && !Arrays.equals(password, confirmField.getText().toCharArray())) {
            showError(Messages.get("users.passwordMismatch"));
            return;
        }
        UserForm form = mode == Mode.PASSWORD ? null : new UserForm(usernameField.getText(), fullNameField.getText(),
                roleCombo.getValue(), Optional.ofNullable(teacherCombo.getValue()).map(TeacherChoice::teacher)
                .map(Teacher::teacherId).orElse(null));
        busy.show(Messages.get("busy.saving"));
        saveButton.setDisable(true);
        FxAsync.run(() -> {
            switch (mode) {
                case CREATE -> users.createUser(form, password);
                case EDIT -> users.updateUser(existing.userId(), form);
                case PASSWORD -> users.resetPassword(existing.userId(), password);
            }
            return true;
        }, ok -> {
            saved = true;
            stage.close();
        }, e -> {
            saveButton.setDisable(false);
            showError(e);
        }, busy::hide);
    }

    @FXML
    private void onCancel() {
        stage.close();
    }

    private void showError(AppException e) {
        showError(e.userMessage());
    }

    private void showError(String text) {
        errorLabel.setText(text);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        stage.sizeToScene();
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private static void show(VBox box, boolean visible) {
        box.setVisible(visible);
        box.setManaged(visible);
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> f) {
        return new StringConverter<>() {
            @Override
            public String toString(T t) {
                return t == null ? "" : f.apply(t);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        };
    }
}
