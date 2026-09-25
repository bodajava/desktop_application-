package com.examhalls.controller;

import com.examhalls.model.AllocationResult;
import com.examhalls.model.Course;
import com.examhalls.model.ExamPeriod;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.ExamOverview;
import com.examhalls.model.ExamStage;
import com.examhalls.model.SeatingResult;
import com.examhalls.security.Permission;
import com.examhalls.security.UserSession;
import com.examhalls.service.ExamManagementService;
import com.examhalls.service.MasterDataService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.Alerts;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.util.Formats;
import com.examhalls.ui.FormDialog;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.ui.View;
import com.examhalls.util.Messages;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;

/** Screen 5 — school exam schedule with seating / allocation actions. */
public class ExamScheduleController {

    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private Button seatButton;
    @FXML private Button allocateButton;
    @FXML private Button openButton;
    @FXML private Button newExamButton;
    @FXML private Button editExamButton;
    @FXML private Button deleteExamButton;
    @FXML private Label selectedLabel;
    @FXML private TableView<ExamOverview> examTable;
    @FXML private TableColumn<ExamOverview, String> dateCol;
    @FXML private TableColumn<ExamOverview, String> periodCol;
    @FXML private TableColumn<ExamOverview, String> courseCol;
    @FXML private TableColumn<ExamOverview, String> gradeCol;
    @FXML private TableColumn<ExamOverview, String> seatedCol;
    @FXML private TableColumn<ExamOverview, Number> proctorsCol;
    @FXML private TableColumn<ExamOverview, ExamStage> statusCol;
    @FXML private TableColumn<ExamOverview, String> notesCol;
    @FXML private Label summaryLabel;
    @FXML private BusyOverlay busy;

    private final ExamManagementService exams = ServiceRegistry.get().examManagementService();

    @FXML
    private void initialize() {
        fromDate.setValue(LocalDate.now());
        toDate.setValue(LocalDate.now().plusMonths(6));

        dateCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formats.date(c.getValue().exam().examDate())));
        periodCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formats.ltr(c.getValue().exam().periodName() + "  ·  "
                + Formats.timeRange(c.getValue().exam().slotStart(), c.getValue().exam().slotEnd()))));
        courseCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().exam().courseCode() + " — " + c.getValue().exam().courseName()));
        gradeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().exam().gradeLevel()));
        seatedCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Formats.ratio(c.getValue().seated(), c.getValue().enrolled())));
        proctorsCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().activeProctors()));
        statusCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().stage()));
        statusCol.setCellFactory(col -> new StageBadgeCell<>());
        notesCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().exam().notes() == null ? "" : c.getValue().exam().notes()));

        examTable.setRowFactory(tv -> {
            TableRow<ExamOverview> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    openOperations(row.getItem().exam().examId());
                }
            });
            return row;
        });
        examTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        fromDate.valueProperty().addListener((o, a, b) -> reload(OptionalLong.empty()));
        toDate.valueProperty().addListener((o, a, b) -> reload(OptionalLong.empty()));

        updateButtons();
        reload(OptionalLong.empty());
    }

    @FXML
    private void onRefresh() {
        reload(selectedId());
    }

    @FXML
    private void onGenerateSeating() {
        ExamOverview row = examTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        if (row.seated() > 0 && !Alerts.confirm(Navigator.get().stage(), Messages.get("schedule.reseat.header"),
                Messages.get("schedule.reseat.text"), Messages.get("action.generateSeating"))) {
            return;
        }
        long examId = row.exam().examId();
        busy.show(Messages.get("busy.seating"));
        FxAsync.run(() -> exams.generateSeating(examId), (SeatingResult r) -> {
            Navigator.get().shell().toast(Messages.get("toast.seated", r.studentsSeated(), r.roomsUsed()));
            reload(OptionalLong.of(examId));
        }, this::fail, busy::hide);
    }

    @FXML
    private void onAllocate() {
        ExamOverview row = examTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        long examId = row.exam().examId();
        busy.show(Messages.get("busy.allocating"));
        FxAsync.run(() -> exams.allocateProctors(examId), (AllocationResult r) -> {
            Navigator.get().shell().toast(Messages.get("toast.allocated", r.assigned()));
            reload(OptionalLong.of(examId));
        }, this::fail, busy::hide);
    }

    @FXML
    private void onOpen() {
        selectedId().ifPresent(this::openOperations);
    }

    private void openOperations(long examId) {
        Navigator.get().shell().<ExamOperationsController>navigate(View.EXAM_OPERATIONS, c -> c.openExam(examId));
    }

    private void reload(OptionalLong reselect) {
        LocalDate from = fromDate.getValue();
        LocalDate to = toDate.getValue();
        if (from == null || to == null) {
            return;
        }
        busy.show(Messages.get("busy.loading"));
        FxAsync.run(() -> exams.getExamOverview(from, to), (List<ExamOverview> rows) -> {
            examTable.setItems(FXCollections.observableArrayList(rows));
            long staffed = rows.stream().filter(r -> r.stage() == ExamStage.STAFFED).count();
            summaryLabel.setText(Messages.get("schedule.summary", rows.size(), staffed));
            reselect.ifPresent(id -> rows.stream().filter(r -> r.exam().examId() == id).findFirst()
                    .ifPresent(r -> examTable.getSelectionModel().select(r)));
            updateButtons();
        }, this::fail, busy::hide);
    }

    // ------------------------------------------------------------------ exam editor (master data)

    @FXML
    private void onNewExam() {
        editExam(null);
    }

    @FXML
    private void onEditExam() {
        ExamOverview row = examTable.getSelectionModel().getSelectedItem();
        if (row != null) {
            editExam(row);
        }
    }

    private void editExam(ExamOverview row) {
        busy.show(Messages.get("busy.loading"));
        MasterDataService data = ServiceRegistry.get().masterDataService();
        // The overlay is hidden before the (blocking) dialog opens, not after the callback returns.
        FxAsync.run(() -> new EditorLists(data.courses(), data.periods()), lists -> {
            busy.hide();
            ExamSchedule e = row == null ? null : row.exam();
            Course course = e == null ? null : lists.courses().stream().filter(c -> c.courseId().equals(e.courseId())).findFirst().orElse(null);
            ExamPeriod period = e == null ? null : lists.periods().stream().filter(p -> p.periodId().equals(e.periodId())).findFirst().orElse(null);
            FormDialog f = new FormDialog(e == null ? "master.exam.new" : "master.exam.edit")
                    .combo("course", "schedule.col.course", lists.courses(),
                            c -> c.courseCode() + " — " + c.courseName() + " (" + c.gradeLevel() + ")", course, true)
                    .combo("period", "schedule.col.period", lists.periods(),
                            p -> p.periodName() + "  ·  " + Formats.timeRange(p.startTime(), p.endTime()), period, true)
                    .date("date", "schedule.col.date", e == null ? fromDate.getValue() : e.examDate())
                    .textArea("notes", "schedule.col.notes", e == null ? "" : e.notes())
                    .note(Messages.get(row != null && row.seated() > 0 ? "master.exam.seatedRule" : "master.exam.rule"));
            f.onSave(() -> {
                Course c = f.value("course");
                ExamPeriod p = f.value("period");
                data.saveExam(new ExamSchedule(e == null ? null : e.examId(), f.date("date"), c.courseId(), null, null,
                        null, p.periodId(), null, null, null, null, f.text("notes")));
            });
            if (f.showAndWait(Navigator.get().stage())) {
                Navigator.get().shell().toast(Messages.get("master.toast.saved"));
                reload(e == null ? OptionalLong.empty() : OptionalLong.of(e.examId()));
            }
        }, ex -> {
            busy.hide();
            fail(ex);
        });
    }

    @FXML
    private void onDeleteExam() {
        ExamOverview row = examTable.getSelectionModel().getSelectedItem();
        if (row == null || !Alerts.confirm(Navigator.get().stage(),
                Messages.get("master.exam.delete", row.exam().courseCode(), Formats.date(row.exam().examDate())),
                Messages.get("master.exam.deleteText"), Messages.get("users.delete"))) {
            return;
        }
        busy.show(Messages.get("busy.saving"));
        FxAsync.run(() -> {
            ServiceRegistry.get().masterDataService().deleteExam(row.exam().examId());
            return true;
        }, ok -> {
            Navigator.get().shell().toast(Messages.get("master.toast.deleted", row.exam().courseCode()));
            reload(OptionalLong.empty());
        }, this::fail, busy::hide);
    }

    private record EditorLists(List<Course> courses, List<ExamPeriod> periods) {
    }

    private OptionalLong selectedId() {
        ExamOverview row = examTable.getSelectionModel().getSelectedItem();
        return row == null ? OptionalLong.empty() : OptionalLong.of(row.exam().examId());
    }

    private void updateButtons() {
        ExamOverview row = examTable.getSelectionModel().getSelectedItem();
        UserSession s = UserSession.get();
        boolean canSeat = s.currentUser().map(u -> u.can(Permission.GENERATE_SEATING)).orElse(false);
        boolean canAllocate = s.currentUser().map(u -> u.can(Permission.ALLOCATE_PROCTORS)).orElse(false);
        seatButton.setVisible(canSeat);
        seatButton.setManaged(canSeat);
        allocateButton.setVisible(canAllocate);
        allocateButton.setManaged(canAllocate);
        boolean canManage = s.currentUser().map(u -> u.can(Permission.MANAGE_MASTER_DATA)).orElse(false);
        for (Button b : List.of(newExamButton, editExamButton, deleteExamButton)) {
            b.setVisible(canManage);
            b.setManaged(canManage);
        }
        editExamButton.setDisable(row == null);
        deleteExamButton.setDisable(row == null || row.activeProctors() > 0);
        selectedLabel.setText(row == null ? Messages.get("master.exam.selectHint")
                : row.exam().courseCode() + " · " + Formats.date(row.exam().examDate()) + " · " + row.exam().periodName());
        seatButton.setDisable(row == null || row.stage() == ExamStage.STAFFED);
        allocateButton.setDisable(row == null || row.stage() != ExamStage.SEATED);
        openButton.setDisable(row == null);
    }

    private void fail(com.examhalls.exception.AppException e) {
        Alerts.error(Navigator.get().stage(), e);
    }

    /** Coloured pill for the exam stage. */
    static final class StageBadgeCell<S> extends TableCell<S, ExamStage> {
        private final Label badge = new Label();

        @Override
        protected void updateItem(ExamStage stage, boolean empty) {
            super.updateItem(stage, empty);
            if (empty || stage == null) {
                setGraphic(null);
                return;
            }
            badge.setText(Formats.enumLabel("stage", stage));
            badge.getStyleClass().setAll("badge", "badge-" + stage.name().toLowerCase().replace('_', '-'));
            setGraphic(badge);
        }
    }
}
