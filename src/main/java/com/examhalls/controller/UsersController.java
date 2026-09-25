package com.examhalls.controller;

import com.examhalls.model.Teacher;
import com.examhalls.model.UserAccount;
import com.examhalls.security.RoleType;
import com.examhalls.security.UserSession;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.service.UserManagementService;
import com.examhalls.ui.Alerts;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Formats;
import com.examhalls.util.Messages;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.Locale;

/** Screens 1 & 2 — user accounts and role assignment. */
public class UsersController {

    private record Data(List<UserAccount> users, List<Teacher> teachers) {
    }

    @FXML private TextField searchField;
    @FXML private Button editButton;
    @FXML private Button resetButton;
    @FXML private Button deleteButton;
    @FXML private TableView<UserAccount> userTable;
    @FXML private TableColumn<UserAccount, String> usernameCol;
    @FXML private TableColumn<UserAccount, String> nameCol;
    @FXML private TableColumn<UserAccount, RoleType> roleCol;
    @FXML private TableColumn<UserAccount, String> teacherCol;
    @FXML private TableColumn<UserAccount, String> createdCol;
    @FXML private Label summaryLabel;
    @FXML private BusyOverlay busy;

    private final UserManagementService users = ServiceRegistry.get().userManagementService();
    private final ObservableList<UserAccount> all = FXCollections.observableArrayList();
    private final FilteredList<UserAccount> filtered = new FilteredList<>(all);
    private List<Teacher> teachers = List.of();

    @FXML
    private void initialize() {
        usernameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().username()));
        nameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().fullName()));
        roleCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().role()));
        roleCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();

            @Override
            protected void updateItem(RoleType role, boolean empty) {
                super.updateItem(role, empty);
                if (empty || role == null) {
                    setGraphic(null);
                    return;
                }
                badge.setText(role.displayName(Messages.currentLocale()));
                badge.getStyleClass().setAll("badge", "badge-role-" + role.name().toLowerCase(Locale.ROOT).replace('_', '-'));
                setGraphic(badge);
            }
        });
        teacherCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().teacherLabel() == null ? "—" : c.getValue().teacherLabel()));
        createdCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().createdAt() == null ? "" : Formats.date(c.getValue().createdAt().toLocalDate())));

        userTable.setItems(filtered);
        userTable.setRowFactory(tv -> {
            TableRow<UserAccount> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    onEdit();
                }
            });
            return row;
        });
        userTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        searchField.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(u -> q.isEmpty() || u.username().toLowerCase(Locale.ROOT).contains(q)
                    || u.fullName().toLowerCase(Locale.ROOT).contains(q)
                    || (u.teacherLabel() != null && u.teacherLabel().toLowerCase(Locale.ROOT).contains(q)));
            updateSummary();
        });
        updateButtons();
        reload();
    }

    @FXML
    private void onNew() {
        if (UserDialogController.show(Navigator.get().stage(), UserDialogController.Mode.CREATE, null, teachers)) {
            Navigator.get().shell().toast(Messages.get("users.toast.created"));
            reload();
        }
    }

    @FXML
    private void onEdit() {
        UserAccount u = userTable.getSelectionModel().getSelectedItem();
        if (u != null && UserDialogController.show(Navigator.get().stage(), UserDialogController.Mode.EDIT, u, teachers)) {
            Navigator.get().shell().toast(Messages.get("users.toast.updated"));
            reload();
        }
    }

    @FXML
    private void onResetPassword() {
        UserAccount u = userTable.getSelectionModel().getSelectedItem();
        if (u != null && UserDialogController.show(Navigator.get().stage(), UserDialogController.Mode.PASSWORD, u, teachers)) {
            Navigator.get().shell().toast(Messages.get("users.toast.password"));
        }
    }

    @FXML
    private void onDelete() {
        UserAccount u = userTable.getSelectionModel().getSelectedItem();
        if (u == null || !Alerts.confirm(Navigator.get().stage(), Messages.get("users.delete.header", u.username()),
                Messages.get("users.delete.text"), Messages.get("users.delete"))) {
            return;
        }
        busy.show(Messages.get("busy.saving"));
        FxAsync.run(() -> {
            users.deleteUser(u.userId());
            return true;
        }, ok -> {
            Navigator.get().shell().toast(Messages.get("users.toast.deleted", u.username()));
            reload();
        }, e -> Alerts.error(Navigator.get().stage(), e), busy::hide);
    }

    private void reload() {
        busy.show(Messages.get("busy.loading"));
        FxAsync.run(() -> new Data(users.listUsers(), users.linkableTeachers()), d -> {
            teachers = d.teachers();
            all.setAll(d.users());
            updateSummary();
        }, e -> Alerts.error(Navigator.get().stage(), e), busy::hide);
    }

    private void updateButtons() {
        UserAccount u = userTable.getSelectionModel().getSelectedItem();
        long me = UserSession.get().requireUser().userId();
        editButton.setDisable(u == null);
        resetButton.setDisable(u == null);
        deleteButton.setDisable(u == null || u.userId() == me);
    }

    private void updateSummary() {
        summaryLabel.setText(Messages.get("users.summary", filtered.size(), all.size()));
    }
}
