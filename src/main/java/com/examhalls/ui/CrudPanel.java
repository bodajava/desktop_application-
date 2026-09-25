package com.examhalls.ui;

import com.examhalls.exception.AppException;
import com.examhalls.util.Messages;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reusable master-data table: search box, New / Edit / Delete, a card with the table and a
 * "showing n of m" line. Loads and deletes off the UI thread through {@link FxAsync}.
 */
public final class CrudPanel<T> extends VBox {

    @FunctionalInterface
    public interface Action<T> {
        void run(T item) throws Exception;
    }

    private final BusyOverlay busy;
    private final ObservableList<T> all = FXCollections.observableArrayList();
    private final FilteredList<T> filtered = new FilteredList<>(all);
    private final TableView<T> table = new TableView<>(filtered);
    private final TextField search = new TextField();
    private final HBox toolbar = new HBox();
    private final Button newButton = new Button(Messages.get("master.new"));
    private final Button editButton = new Button(Messages.get("users.edit"));
    private final Button deleteButton = new Button(Messages.get("users.delete"));
    private final Label summary = new Label();
    private final Label title = new Label();

    private Callable<List<T>> loader = List::of;
    private BiPredicate<T, String> matcher = (t, q) -> true;
    private Consumer<List<T>> afterLoad = l -> { };
    private java.util.function.Predicate<T> extraFilter = t -> true;

    public CrudPanel(BusyOverlay busy) {
        this.busy = busy;
        getStyleClass().add("crud-panel");
        setSpacing(12);

        search.setPromptText(Messages.get("master.search"));
        search.setPrefWidth(280);
        search.textProperty().addListener((o, a, b) -> applyFilter());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        deleteButton.getStyleClass().addAll("button-danger-outline", "toolbar-button");
        editButton.getStyleClass().addAll("button-secondary", "toolbar-button");
        newButton.getStyleClass().addAll("button-primary", "toolbar-button");
        for (Button b : List.of(deleteButton, editButton, newButton)) {
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        toolbar.getStyleClass().add("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getChildren().addAll(search, grow, deleteButton, editButton, newButton);

        title.getStyleClass().add("card-title");
        title.setManaged(false);
        title.setVisible(false);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Label empty = new Label(Messages.get("master.empty"));
        empty.getStyleClass().add("muted");
        table.setPlaceholder(empty);
        VBox.setVgrow(table, Priority.ALWAYS);
        summary.getStyleClass().add("muted");
        VBox card = new VBox(12, title, table, summary);
        card.getStyleClass().addAll("card", "table-card");
        VBox.setVgrow(card, Priority.ALWAYS);

        getChildren().addAll(toolbar, card);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        updateButtons();
    }

    // ------------------------------------------------------------------ configuration

    public CrudPanel<T> title(String text) {
        title.setText(text);
        title.setManaged(true);
        title.setVisible(true);
        return this;
    }

    public CrudPanel<T> column(String headerKey, Function<T, String> value, double width) {
        TableColumn<T, String> c = new TableColumn<>(Messages.get(headerKey));
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setPrefWidth(width);
        table.getColumns().add(c);
        return this;
    }

    /** Pill-shaped cell; {@code style} returns extra style classes such as "badge-staffed" (null = plain text). */
    public CrudPanel<T> badgeColumn(String headerKey, Function<T, String> text, Function<T, String> style, double width) {
        TableColumn<T, T> c = new TableColumn<>(Messages.get(headerKey));
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                String s = empty || item == null ? null : text.apply(item);
                if (s == null || s.isEmpty()) {
                    setGraphic(null);
                    return;
                }
                badge.setText(s);
                String cls = style.apply(item);
                badge.getStyleClass().setAll(cls == null ? List.of("cell-text") : List.of("badge", cls));
                setGraphic(badge);
            }
        });
        c.setPrefWidth(width);
        table.getColumns().add(c);
        return this;
    }

    public CrudPanel<T> loader(Callable<List<T>> loader) {
        this.loader = loader;
        return this;
    }

    public CrudPanel<T> searchable(BiPredicate<T, String> matcher) {
        this.matcher = matcher;
        return this;
    }

    public CrudPanel<T> afterLoad(Consumer<List<T>> callback) {
        this.afterLoad = callback;
        return this;
    }

    public CrudPanel<T> onNew(Runnable r) {
        newButton.setOnAction(e -> r.run());
        return this;
    }

    public CrudPanel<T> onEdit(Consumer<T> c) {
        editButton.setOnAction(e -> {
            T sel = selected();
            if (sel != null) {
                c.accept(sel);
            }
        });
        table.setRowFactory(tv -> {
            TableRow<T> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && !editButton.isDisabled()) {
                    c.accept(row.getItem());
                }
            });
            return row;
        });
        return this;
    }

    /**
     * Delete with confirmation. {@code header} builds the question ("Delete room A101?").
     * Errors (e.g. "still used by upcoming exams") are shown in an alert.
     */
    public CrudPanel<T> onDelete(Function<T, String> header, String detailKey, Action<T> delete, Function<T, String> doneToast) {
        deleteButton.setOnAction(e -> {
            T sel = selected();
            if (sel == null || !Alerts.confirm(Navigator.get().stage(), header.apply(sel), Messages.get(detailKey),
                    Messages.get("users.delete"))) {
                return;
            }
            busy.show(Messages.get("busy.saving"));
            FxAsync.run(() -> {
                delete.run(sel);
                return true;
            }, ok -> {
                Navigator.get().shell().toast(doneToast.apply(sel));
                reload();
            }, (AppException ex) -> Alerts.error(Navigator.get().stage(), ex), busy::hide);
        });
        return this;
    }

    /** Hides New / Edit / Delete (read-only role). */
    public CrudPanel<T> readOnly(boolean readOnly) {
        for (Button b : List.of(newButton, editButton, deleteButton)) {
            b.setVisible(!readOnly);
            b.setManaged(!readOnly);
        }
        return this;
    }

    public CrudPanel<T> hideEditButton() {
        editButton.setVisible(false);
        editButton.setManaged(false);
        return this;
    }

    /** Disables New (and Edit/Delete) e.g. while no parent row is selected. */
    public void setActionsEnabled(boolean enabled) {
        newButton.setDisable(!enabled);
        if (!enabled) {
            editButton.setDisable(true);
            deleteButton.setDisable(true);
        } else {
            updateButtons();
        }
    }

    public CrudPanel<T> hideDelete() {
        deleteButton.setVisible(false);
        deleteButton.setManaged(false);
        return this;
    }

    /** Extra control in the toolbar, placed before the action buttons (e.g. a filter combo). */
    public CrudPanel<T> toolbarNode(Node n) {
        toolbar.getChildren().add(1, n);
        return this;
    }

    // ------------------------------------------------------------------ runtime

    public void reload() {
        T keep = selected();
        busy.show(Messages.get("busy.loading"));
        FxAsync.run(loader, items -> {
            all.setAll(items);
            applyFilter();
            if (keep != null) {
                items.stream().filter(i -> i.equals(keep)).findFirst().ifPresent(i -> table.getSelectionModel().select(i));
            }
            afterLoad.accept(items);
        }, (AppException ex) -> Alerts.error(Navigator.get().stage(), ex), busy::hide);
    }

    /** Re-applies the search (e.g. after an external filter changed). */
    public void applyFilter() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        filtered.setPredicate(t -> extraFilter.test(t) && (q.isEmpty() || matcher.test(t, q)));
        summary.setText(Messages.get("master.summary", filtered.size(), all.size()));
    }

    public T selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    public ReadOnlyObjectProperty<T> selectedItemProperty() {
        return table.getSelectionModel().selectedItemProperty();
    }

    public TableView<T> table() {
        return table;
    }

    /** An additional filter combined with the search text (e.g. a grade-level picker). */
    public void setExtraFilter(java.util.function.Predicate<T> extra) {
        this.extraFilter = extra;
        applyFilter();
    }

    private void updateButtons() {
        boolean none = selected() == null;
        editButton.setDisable(none);
        deleteButton.setDisable(none);
    }

    /** Case-insensitive "contains" over several fields — convenience for {@link #searchable}. */
    @SafeVarargs
    public static <T> BiPredicate<T, String> anyOf(Function<T, String>... fields) {
        return (t, q) -> {
            for (Function<T, String> f : fields) {
                String v = f.apply(t);
                if (v != null && v.toLowerCase(Locale.ROOT).contains(q)) {
                    return true;
                }
            }
            return false;
        };
    }
}
