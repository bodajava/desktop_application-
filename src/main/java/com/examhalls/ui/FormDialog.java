package com.examhalls.ui;

import com.examhalls.exception.AppException;
import com.examhalls.util.Messages;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Small builder for the master-data forms: labelled fields in one card, inline error, Cancel/Save.
 * Required fields are checked locally (with translated labels) before {@code onSave} runs on a
 * worker thread; service errors are shown inline and the dialog stays open.
 *
 * <pre>{@code
 * FormDialog f = new FormDialog("master.room.new")
 *         .text("code", "master.field.roomCode", room.roomCode(), true)
 *         .integer("cap", "master.field.examCapacity", 1, 9999, room.examCapacity());
 * f.onSave(() -> service.saveRoom(new Room(..., f.text("code"), f.integer("cap"), ...)));
 * boolean saved = f.showAndWait(owner);
 * }</pre>
 */
public final class FormDialog {

    @FunctionalInterface
    public interface SaveAction {
        void run() throws Exception;
    }

    private record Field(String labelKey, Node control, boolean required) {
    }

    private final String titleKey;
    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final List<Node> extras = new ArrayList<>();
    private String subtitle;
    private SaveAction onSave;
    private Function<FormDialog, Optional<String>> validator = f -> Optional.empty();
    private boolean saved;

    public FormDialog(String titleKey) {
        this.titleKey = titleKey;
    }

    public FormDialog subtitle(String text) {
        this.subtitle = text;
        return this;
    }

    // ------------------------------------------------------------------ fields

    public FormDialog text(String id, String labelKey, String value, boolean required) {
        TextField t = new TextField(value == null ? "" : value);
        return add(id, labelKey, t, required);
    }

    public FormDialog password(String id, String labelKey, boolean required) {
        PasswordField p = new PasswordField();
        return add(id, labelKey, p, required);
    }

    public FormDialog textArea(String id, String labelKey, String value) {
        TextArea t = new TextArea(value == null ? "" : value);
        t.setPrefRowCount(3);
        t.setWrapText(true);
        return add(id, labelKey, t, false);
    }

    public FormDialog integer(String id, String labelKey, int min, int max, int value) {
        Spinner<Integer> s = new Spinner<>(min, max, Math.max(min, Math.min(max, value)));
        s.setEditable(true);
        s.setMaxWidth(Double.MAX_VALUE);
        return add(id, labelKey, s, true);
    }

    public <T> FormDialog combo(String id, String labelKey, List<T> items, Function<T, String> display, T value,
                                boolean required) {
        ComboBox<T> c = new ComboBox<>(FXCollections.observableArrayList(items));
        c.setMaxWidth(Double.MAX_VALUE);
        c.setConverter(new StringConverter<>() {
            @Override
            public String toString(T t) {
                return t == null ? "" : display.apply(t);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        });
        c.setValue(value);
        return add(id, labelKey, c, required);
    }

    /** Editable combo: pick an existing value or type a new one (e.g. grade level). */
    public FormDialog editableCombo(String id, String labelKey, List<String> items, String value, boolean required) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(items));
        c.setEditable(true);
        c.setMaxWidth(Double.MAX_VALUE);
        c.setValue(value);
        c.getEditor().setText(value == null ? "" : value);
        return add(id, labelKey, c, required);
    }

    public FormDialog date(String id, String labelKey, LocalDate value) {
        DatePicker d = new DatePicker(value);
        d.setMaxWidth(Double.MAX_VALUE);
        return add(id, labelKey, d, true);
    }

    public FormDialog checkbox(String id, String labelKey, boolean value) {
        CheckBox c = new CheckBox(Messages.get(labelKey));
        c.setSelected(value);
        fields.put(id, new Field(null, c, false));
        return this;
    }

    /** Read-only value shown in the form (e.g. hours balance). */
    public FormDialog info(String labelKey, String value) {
        Label v = new Label(value);
        v.getStyleClass().add("detail-value");
        fields.put("info:" + labelKey, new Field(labelKey, v, false));
        return this;
    }

    /** A hint line under the fields. */
    public FormDialog note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        extras.add(l);
        return this;
    }

    public FormDialog validator(Function<FormDialog, Optional<String>> v) {
        this.validator = v;
        return this;
    }

    public FormDialog onSave(SaveAction action) {
        this.onSave = action;
        return this;
    }

    // ------------------------------------------------------------------ values

    public String text(String id) {
        Node n = fields.get(id).control();
        String v;
        if (n instanceof ComboBox<?> c && c.isEditable()) {
            v = c.getEditor().getText();
        } else if (n instanceof TextArea a) {
            v = a.getText();
        } else {
            v = ((TextField) n).getText();
        }
        return v == null ? "" : v.trim();
    }

    @SuppressWarnings("unchecked")
    public int integer(String id) {
        Spinner<Integer> s = (Spinner<Integer>) fields.get(id).control();
        commit(s);
        return s.getValue();
    }

    @SuppressWarnings("unchecked")
    public <T> T value(String id) {
        return ((ComboBox<T>) fields.get(id).control()).getValue();
    }

    public LocalDate date(String id) {
        DatePicker d = (DatePicker) fields.get(id).control();
        return d.getValue();
    }

    public boolean bool(String id) {
        return ((CheckBox) fields.get(id).control()).isSelected();
    }

    // ------------------------------------------------------------------ show

    /** @return true if {@code onSave} completed successfully */
    public boolean showAndWait(Window owner) {
        VBox form = new VBox(12);
        form.getStyleClass().addAll("card", "card-compact");
        for (Field f : fields.values()) {
            if (f.labelKey() == null) {
                form.getChildren().add(f.control());
                continue;
            }
            Label label = new Label(Messages.get(f.labelKey()) + (f.required() ? " *" : ""));
            label.getStyleClass().add("field-label");
            VBox box = new VBox(6, label, f.control());
            box.getStyleClass().add("form-field");
            form.getChildren().add(box);
        }
        form.getChildren().addAll(extras);

        Label title = new Label(Messages.get(titleKey));
        title.getStyleClass().add("page-title");
        VBox header = new VBox(4, title);
        header.getStyleClass().add("dialog-header");
        if (subtitle != null) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().add("muted");
            sub.setWrapText(true);
            header.getChildren().add(sub);
        }

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        error.setVisible(false);
        error.setManaged(false);

        Button cancel = new Button(Messages.get("common.cancel"));
        cancel.getStyleClass().add("button-secondary");
        cancel.setCancelButton(true);
        Button save = new Button(Messages.get("users.save"));
        save.getStyleClass().add("button-primary");
        save.setDefaultButton(true);
        HBox buttons = new HBox(12, cancel, save);
        buttons.getStyleClass().add("dialog-buttons");
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(16, header, form, error, buttons);
        content.getStyleClass().add("dialog-content");
        BusyOverlay busy = new BusyOverlay();
        StackPane root = new StackPane(content, busy);
        root.getStyleClass().add("app-root");
        root.setPrefWidth(480);
        Navigator.applyLanguage(root);

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(title.getText());
        Scene scene = new Scene(root);
        scene.getStylesheets().add(Navigator.stylesheet());
        stage.setScene(scene);
        stage.setResizable(false);

        cancel.setOnAction(e -> stage.close());
        save.setOnAction(e -> {
            Optional<String> problem = localCheck().or(() -> validator.apply(this));
            if (problem.isPresent()) {
                show(error, problem.get(), stage);
                return;
            }
            error.setVisible(false);
            error.setManaged(false);
            save.setDisable(true);
            busy.show(Messages.get("busy.saving"));
            FxAsync.run(() -> {
                onSave.run();
                return true;
            }, ok -> {
                saved = true;
                stage.close();
            }, (AppException ex) -> {
                save.setDisable(false);
                show(error, ex.userMessage(), stage);
            }, busy::hide);
        });
        stage.showAndWait();
        return saved;
    }

    // ------------------------------------------------------------------ internals

    private FormDialog add(String id, String labelKey, Node control, boolean required) {
        fields.put(id, new Field(labelKey, control, required));
        return this;
    }

    /** Required fields must not be empty. */
    private Optional<String> localCheck() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            Field f = e.getValue();
            if (!f.required()) {
                continue;
            }
            Node n = f.control();
            boolean empty = n instanceof TextField t ? t.getText().isBlank()
                    : n instanceof ComboBox<?> c ? (c.isEditable() ? c.getEditor().getText().isBlank() : c.getValue() == null)
                    : n instanceof DatePicker d && d.getValue() == null;
            if (empty) {
                missing.add(Messages.get(f.labelKey()));
            }
        }
        return missing.isEmpty() ? Optional.empty()
                : Optional.of(Messages.get("error.VALIDATION_FAILED", String.join(Messages.isRightToLeft() ? "، " : ", ", missing)));
    }

    private static void show(Label error, String text, Stage stage) {
        error.setText(text);
        error.setVisible(true);
        error.setManaged(true);
        stage.sizeToScene();
    }

    /** Spinners only commit typed text on focus loss; commit explicitly before reading. */
    private static void commit(Spinner<Integer> s) {
        try {
            s.getValueFactory().setValue(s.getValueFactory().getConverter().fromString(s.getEditor().getText()));
        } catch (RuntimeException ignored) {
            s.getEditor().setText(String.valueOf(s.getValue()));
        }
    }
}
