package com.examhalls.controller;

import com.examhalls.exception.AppException;
import com.examhalls.model.ExamOverview;
import com.examhalls.model.SubstitutionResult;
import com.examhalls.model.SupervisionRoster;
import com.examhalls.model.TeacherCandidate;
import com.examhalls.service.ExamManagementService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.util.Formats;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Messages;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Screen 7 — emergency substitution. Shows the best eligible substitute for the 1-click path and
 * the full candidate list (eligible = free at that time and zero rule conflicts) for a manual pick.
 * The action is logged in SUPERVISION_AUDIT by pkg_supervision.replace_proctor.
 */
public class SubstitutionDialogController {

    private record Candidates(List<TeacherCandidate> all, OptionalLong suggested) {
    }

    @FXML private Label teacherValue;
    @FXML private Label roleValue;
    @FXML private Label roomValue;
    @FXML private Label examValue;
    @FXML private HBox suggestionBox;
    @FXML private Label suggestionLabel;
    @FXML private Label suggestionDetail;
    @FXML private Button oneClickButton;
    @FXML private Label candidatesTitle;
    @FXML private CheckBox showAllCheck;
    @FXML private TableView<TeacherCandidate> candidateTable;
    @FXML private TableColumn<TeacherCandidate, String> cNameCol;
    @FXML private TableColumn<TeacherCandidate, String> cCodeCol;
    @FXML private TableColumn<TeacherCandidate, String> cDeptCol;
    @FXML private TableColumn<TeacherCandidate, String> cHoursCol;
    @FXML private TableColumn<TeacherCandidate, TeacherCandidate> cStatusCol;
    @FXML private ComboBox<String> reasonCombo;
    @FXML private Label errorLabel;
    @FXML private Button manualButton;
    @FXML private BusyOverlay busy;

    private final ExamManagementService exams = ServiceRegistry.get().examManagementService();
    private final ObservableList<TeacherCandidate> allCandidates = FXCollections.observableArrayList();
    private final FilteredList<TeacherCandidate> candidates = new FilteredList<>(allCandidates);
    private Stage stage;
    private SupervisionRoster entry;
    private Long suggestedTeacherId;
    private SubstitutionResult result;

    /** Opens the modal and blocks until it is closed. */
    public static Optional<SubstitutionResult> show(Window owner, SupervisionRoster entry, ExamOverview exam) {
        Navigator.Loaded<SubstitutionDialogController> loaded = Navigator.load("SubstitutionDialog");
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(Messages.get("sub.title"));
        Scene scene = new Scene(loaded.root());
        scene.getStylesheets().add(Navigator.stylesheet());
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(620);
        SubstitutionDialogController c = loaded.controller();
        c.init(stage, entry, exam);
        stage.showAndWait();
        return Optional.ofNullable(c.result);
    }

    @FXML
    private void initialize() {
        cNameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().fullName()));
        cCodeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().teacherCode()));
        cDeptCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().deptName()));
        cHoursCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formats.hours(c.getValue().hoursBalance())));
        cStatusCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        cStatusCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();

            @Override
            protected void updateItem(TeacherCandidate t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) {
                    setGraphic(null);
                    return;
                }
                badge.setText(t.reasonText(Messages.currentLocale()));
                badge.getStyleClass().setAll("badge", t.eligible() ? "badge-staffed" : "badge-blocked");
                setGraphic(badge);
            }
        });
        candidateTable.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(TeacherCandidate t, boolean empty) {
                super.updateItem(t, empty);
                getStyleClass().remove("row-disabled");
                if (!empty && t != null && !t.eligible()) {
                    getStyleClass().add("row-disabled");
                }
            }
        });
        candidateTable.setItems(candidates);
        candidateTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        showAllCheck.selectedProperty().addListener((o, a, b) -> applyFilter());
        applyFilter();

        reasonCombo.getItems().setAll(Messages.get("sub.reason.sick"), Messages.get("sub.reason.emergency"),
                Messages.get("sub.reason.absent"), Messages.get("sub.reason.conflict"));
        reasonCombo.getEditor().textProperty().addListener((o, a, b) -> hideError());
        manualButton.setDisable(true);
        oneClickButton.setDisable(true);
    }

    private void init(Stage stage, SupervisionRoster entry, ExamOverview exam) {
        this.stage = stage;
        this.entry = entry;
        teacherValue.setText(entry.teacherName());
        roleValue.setText(Formats.enumLabel("supervisionRole", entry.roleType()));
        roomValue.setText(entry.roomCode());
        examValue.setText(exam.exam().courseCode() + " · " + Formats.date(exam.exam().examDate()) + " · "
                + Formats.timeRange(exam.exam().slotStart(), exam.exam().slotEnd()));

        busy.show(Messages.get("busy.candidates"));
        FxAsync.run(() -> new Candidates(
                        exams.getCandidates(entry.examId(), entry.roomId()).stream()
                                .filter(c -> c.teacherId() != entry.teacherId()).toList(),
                        exams.suggestSubstitute(entry.rosterId())),
                this::showCandidates,
                this::showError,
                busy::hide);
    }

    private void showCandidates(Candidates data) {
        allCandidates.setAll(data.all());
        long eligible = data.all().stream().filter(TeacherCandidate::eligible).count();
        candidatesTitle.setText(Messages.get("sub.candidatesCount", eligible));

        Optional<TeacherCandidate> best = data.suggested().isPresent()
                ? data.all().stream().filter(c -> c.teacherId() == data.suggested().getAsLong()).findFirst()
                : Optional.empty();
        if (best.isPresent()) {
            TeacherCandidate b = best.get();
            suggestedTeacherId = b.teacherId();
            suggestionLabel.setText(b.fullName() + "  (" + b.teacherCode() + ")");
            suggestionDetail.setText(Messages.get("sub.bestMatchDetail", b.deptName(), Formats.hours(b.hoursBalance())));
            oneClickButton.setDisable(false);
            suggestionBox.getStyleClass().remove("suggestion-none");
            candidateTable.getSelectionModel().select(b);       // manual path starts on the best match too
            candidateTable.scrollTo(b);
        } else {
            suggestionLabel.setText(Messages.get("sub.noSuggestion"));
            suggestionDetail.setText("");
            oneClickButton.setDisable(true);
            suggestionBox.getStyleClass().add("suggestion-none");
        }
    }

    @FXML
    private void onOneClick() {
        if (suggestedTeacherId != null) {
            substitute(suggestedTeacherId);
        }
    }

    @FXML
    private void onUseSelected() {
        TeacherCandidate selected = candidateTable.getSelectionModel().getSelectedItem();
        if (selected != null && selected.eligible()) {
            substitute(selected.teacherId());
        }
    }

    @FXML
    private void onCancel() {
        stage.close();
    }

    /** Uses the explicit teacher id so what the user saw is exactly what gets assigned. */
    private void substitute(long teacherId) {
        String reason = reasonCombo.getEditor().getText();
        if (reason == null || reason.isBlank()) {
            showError(Messages.get("sub.reasonRequired"));
            reasonCombo.requestFocus();
            return;
        }
        busy.show(Messages.get("busy.substituting"));
        setButtonsDisabled(true);
        FxAsync.run(() -> exams.substituteProctor(entry.rosterId(), OptionalLong.of(teacherId), reason.trim()),
                r -> {
                    result = r;
                    stage.close();
                },
                e -> {
                    setButtonsDisabled(false);
                    showError(e);
                },
                busy::hide);
    }

    private void applyFilter() {
        boolean all = showAllCheck.isSelected();
        candidates.setPredicate(c -> all || c.eligible());
    }

    private void updateButtons() {
        TeacherCandidate selected = candidateTable.getSelectionModel().getSelectedItem();
        manualButton.setDisable(selected == null || !selected.eligible());
    }

    private void setButtonsDisabled(boolean disabled) {
        oneClickButton.setDisable(disabled || suggestedTeacherId == null);
        if (disabled) {
            manualButton.setDisable(true);
        } else {
            updateButtons();
        }
    }

    private void showError(AppException e) {
        showError(e.userMessage() + (e.detail() != null ? "\n" + e.detail() : ""));
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
