package com.examhalls.ui;

import com.examhalls.exception.AppException;
import com.examhalls.util.Messages;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Window;

import java.util.Optional;

/** Styled, localised dialogs. */
public final class Alerts {

    private Alerts() {
    }

    /** Shows the translated message, with the technical detail (if any) underneath. */
    public static void error(Window owner, AppException e) {
        Alert alert = base(Alert.AlertType.ERROR, owner, Messages.get("ui.alert.errorTitle"));
        alert.setHeaderText(e.userMessage());
        if (e.detail() != null && !e.detail().isBlank()) {
            Label detail = new Label(e.detail());
            detail.setWrapText(true);
            detail.getStyleClass().add("alert-detail");
            alert.getDialogPane().setContent(detail);
        }
        alert.showAndWait();
    }

    public static void info(Window owner, String header, String text) {
        Alert alert = base(Alert.AlertType.INFORMATION, owner, Messages.get("ui.alert.infoTitle"));
        alert.setHeaderText(header);
        alert.setContentText(text);
        alert.showAndWait();
    }

    public static boolean confirm(Window owner, String header, String text, String confirmLabel) {
        Alert alert = base(Alert.AlertType.CONFIRMATION, owner, Messages.get("ui.alert.confirmTitle"));
        alert.setHeaderText(header);
        alert.setContentText(text);
        ButtonType ok = new ButtonType(confirmLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(Messages.get("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(ok, cancel);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ok;
    }

    private static Alert base(Alert.AlertType type, Window owner, String title) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        if (owner != null) {
            alert.initOwner(owner);
        }
        DialogPane pane = alert.getDialogPane();
        pane.getStylesheets().add(Navigator.stylesheet());
        pane.getStyleClass().add("app-dialog");
        Navigator.applyLanguage(pane);
        pane.setMinWidth(440);
        return alert;
    }
}
