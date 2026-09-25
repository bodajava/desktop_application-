package com.examhalls.controller;

import com.examhalls.exception.AppException;
import com.examhalls.model.AllocationResult;
import com.examhalls.model.ExamOverview;
import com.examhalls.model.ExamStage;
import com.examhalls.model.RoomAllocationSummary;
import com.examhalls.model.SeatingAllocation;
import com.examhalls.model.SeatingResult;
import com.examhalls.model.SubstitutionResult;
import com.examhalls.model.SupervisionRole;
import com.examhalls.model.SupervisionRoster;
import com.examhalls.security.Permission;
import com.examhalls.security.UserSession;
import com.examhalls.service.ExamManagementService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.Alerts;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.util.Formats;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.util.Messages;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Screens 4 & 6 — seating allocation and the supervision roster matrix for one exam. */
public class ExamOperationsController {

    private record ExamData(List<RoomAllocationSummary> rooms, List<SeatingAllocation> seats) {
    }

    @FXML private ComboBox<ExamOverview> examCombo;
    @FXML private Button seatButton;
    @FXML private Button allocateButton;
    @FXML private Button cancelButton;
    @FXML private Button substituteButton;
    @FXML private Label slotValue;
    @FXML private Label seatedValue;
    @FXML private Label roomsValue;
    @FXML private Label staffValue;
    @FXML private Label specialValue;
    @FXML private Label stageBadge;
    @FXML private Label roomTitle;

    @FXML private TableView<RoomAllocationSummary> matrixTable;
    @FXML private TableColumn<RoomAllocationSummary, String> mRoomCol;
    @FXML private TableColumn<RoomAllocationSummary, String> mBuildingCol;
    @FXML private TableColumn<RoomAllocationSummary, RoomAllocationSummary> mOccupancyCol;
    @FXML private TableColumn<RoomAllocationSummary, Number> mSpecialCol;
    @FXML private TableColumn<RoomAllocationSummary, String> mHeadCol;
    @FXML private TableColumn<RoomAllocationSummary, String> mProctorsCol;

    @FXML private TableView<SupervisionRoster> staffTable;
    @FXML private TableColumn<SupervisionRoster, SupervisionRole> sRoleCol;
    @FXML private TableColumn<SupervisionRoster, String> sTeacherCol;
    @FXML private TableColumn<SupervisionRoster, String> sCodeCol;
    @FXML private TableColumn<SupervisionRoster, String> sExamCol;

    @FXML private TableView<SeatingAllocation> seatTable;
    @FXML private TableColumn<SeatingAllocation, String> seatNoCol;
    @FXML private TableColumn<SeatingAllocation, String> seatCodeCol;
    @FXML private TableColumn<SeatingAllocation, String> seatNameCol;
    @FXML private TableColumn<SeatingAllocation, Boolean> seatNeedsCol;
    @FXML private TableColumn<SeatingAllocation, String> seatAttendanceCol;

    @FXML private BusyOverlay busy;

    private final ExamManagementService exams = ServiceRegistry.get().examManagementService();
    private List<SeatingAllocation> currentSeats = List.of();
    private Long pendingExamId;

    @FXML
    private void initialize() {
        examCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ExamOverview o) {
                return o == null ? "" : describe(o);
            }

            @Override
            public ExamOverview fromString(String s) {
                return null;
            }
        });
        examCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ExamOverview o, boolean empty) {
                super.updateItem(o, empty);
                setText(empty || o == null ? null : describe(o));
            }
        });
        examCombo.valueProperty().addListener((o, a, b) -> loadExam());

        setUpMatrix();
        setUpStaffTable();
        setUpSeatTable();

        boolean canSeat = can(Permission.GENERATE_SEATING);
        boolean canAllocate = can(Permission.ALLOCATE_PROCTORS);
        boolean canSubstitute = can(Permission.SUBSTITUTE_PROCTOR);
        show(seatButton, canSeat);
        show(allocateButton, canAllocate);
        show(cancelButton, canAllocate);
        show(substituteButton, canSubstitute);

        clearExam();
        loadExamList();
    }

    /** Called by the schedule screen to open a specific exam. */
    public void openExam(long examId) {
        pendingExamId = examId;
        selectPending();
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void onGenerateSeating() {
        ExamOverview exam = examCombo.getValue();
        if (exam == null) {
            return;
        }
        if (exam.seated() > 0 && !Alerts.confirm(Navigator.get().stage(), Messages.get("schedule.reseat.header"),
                Messages.get("schedule.reseat.text"), Messages.get("action.generateSeating"))) {
            return;
        }
        long id = exam.exam().examId();
        busy.show(Messages.get("busy.seating"));
        FxAsync.run(() -> exams.generateSeating(id), (SeatingResult r) -> {
            Navigator.get().shell().toast(Messages.get("toast.seated", r.studentsSeated(), r.roomsUsed()));
            reloadKeepingSelection(id);
        }, this::fail, busy::hide);
    }

    @FXML
    private void onAllocate() {
        ExamOverview exam = examCombo.getValue();
        if (exam == null) {
            return;
        }
        long id = exam.exam().examId();
        busy.show(Messages.get("busy.allocating"));
        FxAsync.run(() -> exams.allocateProctors(id), (AllocationResult r) -> {
            Navigator.get().shell().toast(Messages.get("toast.allocated", r.assigned()));
            reloadKeepingSelection(id);
        }, this::fail, busy::hide);
    }

    @FXML
    private void onCancelAllocation() {
        ExamOverview exam = examCombo.getValue();
        if (exam == null || !Alerts.confirm(Navigator.get().stage(), Messages.get("ops.cancel.header"),
                Messages.get("ops.cancel.text"), Messages.get("action.cancelAllocation"))) {
            return;
        }
        long id = exam.exam().examId();
        busy.show(Messages.get("busy.cancelling"));
        FxAsync.run(() -> exams.cancelAllocation(id), (Integer n) -> {
            Navigator.get().shell().toast(Messages.get("toast.cancelled", n));
            reloadKeepingSelection(id);
        }, this::fail, busy::hide);
    }

    @FXML
    private void onSubstitute() {
        SupervisionRoster entry = staffTable.getSelectionModel().getSelectedItem();
        ExamOverview exam = examCombo.getValue();
        if (entry == null || exam == null) {
            return;
        }
        Optional<SubstitutionResult> result = SubstitutionDialogController.show(Navigator.get().stage(), entry, exam);
        result.ifPresent(r -> {
            Navigator.get().shell().toast(Messages.get("toast.substituted", r.auditId()));
            reloadKeepingSelection(exam.exam().examId());
        });
    }

    // ------------------------------------------------------------------ loading

    private void loadExamList() {
        busy.show(Messages.get("busy.loading"));
        FxAsync.run(() -> exams.getExamOverview(LocalDate.now(), LocalDate.now().plusYears(1)), rows -> {
            examCombo.setItems(FXCollections.observableArrayList(rows));
            selectPending();
        }, this::fail, busy::hide);
    }

    private void selectPending() {
        if (pendingExamId == null || examCombo.getItems().isEmpty()) {
            return;
        }
        long id = pendingExamId;
        pendingExamId = null;
        examCombo.getItems().stream().filter(o -> o.exam().examId() == id).findFirst().ifPresent(examCombo::setValue);
    }

    private void reloadKeepingSelection(long examId) {
        pendingExamId = examId;
        loadExamList();
    }

    private void loadExam() {
        ExamOverview exam = examCombo.getValue();
        if (exam == null) {
            clearExam();
            return;
        }
        long id = exam.exam().examId();
        showHeader(exam);
        busy.show(Messages.get("busy.loading"));
        FxAsync.run(() -> new ExamData(exams.getRoomSummaries(id), exams.getSeating(id)), data -> {
            currentSeats = data.seats();
            matrixTable.setItems(FXCollections.observableArrayList(data.rooms()));
            int staff = data.rooms().stream().mapToInt(r -> r.staff().size()).sum();
            int special = data.rooms().stream().mapToInt(RoomAllocationSummary::specialNeeds).sum();
            staffValue.setText(String.valueOf(staff));
            specialValue.setText(String.valueOf(special));
            if (!data.rooms().isEmpty()) {
                matrixTable.getSelectionModel().selectFirst();
            } else {
                showRoom(null);
            }
        }, this::fail, busy::hide);
    }

    private void showHeader(ExamOverview o) {
        slotValue.setText(Formats.date(o.exam().examDate()) + "  ·  "
                + Formats.timeRange(o.exam().slotStart(), o.exam().slotEnd()));
        seatedValue.setText(Formats.ratio(o.seated(), o.enrolled()));
        roomsValue.setText(String.valueOf(o.roomsUsed()));
        stageBadge.setText(Formats.enumLabel("stage", o.stage()));
        stageBadge.getStyleClass().setAll("badge", "badge-" + o.stage().name().toLowerCase().replace('_', '-'));
        stageBadge.setVisible(true);

        seatButton.setDisable(o.stage() == ExamStage.STAFFED);
        allocateButton.setDisable(o.stage() != ExamStage.SEATED);
        cancelButton.setDisable(o.activeProctors() == 0);
    }

    private void clearExam() {
        for (Label l : List.of(slotValue, seatedValue, roomsValue, staffValue, specialValue)) {
            l.setText("—");
        }
        stageBadge.setVisible(false);
        seatButton.setDisable(true);
        allocateButton.setDisable(true);
        cancelButton.setDisable(true);
        matrixTable.getItems().clear();
        showRoom(null);
    }

    private void showRoom(RoomAllocationSummary room) {
        if (room == null) {
            roomTitle.setText(Messages.get("ops.room.none"));
            staffTable.getItems().clear();
            seatTable.getItems().clear();
            substituteButton.setDisable(true);
            return;
        }
        roomTitle.setText(Messages.get("ops.room.title", Formats.ltr(room.roomCode()), Formats.ltr(room.building())));
        staffTable.setItems(FXCollections.observableArrayList(room.staff()));
        seatTable.setItems(FXCollections.observableArrayList(currentSeats.stream()
                .filter(s -> s.roomId() == room.roomId()).toList()));
        substituteButton.setDisable(true);
    }

    // ------------------------------------------------------------------ table setup

    private void setUpMatrix() {
        mRoomCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().roomCode()));
        mBuildingCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().building()));
        mOccupancyCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        mOccupancyCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            private final Label text = new Label();
            private final HBox box = new HBox(8, bar, text);

            {
                box.setAlignment(Pos.CENTER_LEFT);
                bar.setPrefWidth(80);
                bar.getStyleClass().add("occupancy-bar");
            }

            @Override
            protected void updateItem(RoomAllocationSummary r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    setGraphic(null);
                    return;
                }
                bar.setProgress(r.examCapacity() == 0 ? 0 : (double) r.seatedThisExam() / r.examCapacity());
                text.setText(Formats.ratio(r.seatedThisExam(), r.examCapacity()));
                setGraphic(box);
            }
        });
        mSpecialCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().specialNeeds()));
        mHeadCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(names(c.getValue().heads())));
        mProctorsCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(names(c.getValue().proctors())));
        mHeadCol.setCellFactory(col -> new MissingStaffCell());
        mProctorsCol.setCellFactory(col -> new MissingStaffCell());
        matrixTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showRoom(b));
    }

    private void setUpStaffTable() {
        sRoleCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().roleType()));
        sRoleCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();

            @Override
            protected void updateItem(SupervisionRole role, boolean empty) {
                super.updateItem(role, empty);
                if (empty || role == null) {
                    setGraphic(null);
                    return;
                }
                badge.setText(Formats.enumLabel("supervisionRole", role));
                badge.getStyleClass().setAll("badge",
                        role == SupervisionRole.HEAD_OF_COMMITTEE ? "badge-head" : "badge-proctor");
                setGraphic(badge);
            }
        });
        sTeacherCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().teacherName()));
        sCodeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().teacherCode()));
        sExamCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().courseCode()));
        staffTable.getSelectionModel().selectedItemProperty().addListener(
                (o, a, b) -> substituteButton.setDisable(b == null));
    }

    private void setUpSeatTable() {
        seatNoCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().seatNumber()));
        seatCodeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().studentCode()));
        seatNameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().studentName()));
        seatNeedsCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().hasSpecialNeeds()));
        seatNeedsCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();

            @Override
            protected void updateItem(Boolean needs, boolean empty) {
                super.updateItem(needs, empty);
                if (empty || needs == null || !needs) {
                    setGraphic(null);
                    return;
                }
                badge.setText(Messages.get("ops.specialNeeds"));
                badge.getStyleClass().setAll("badge", "badge-special");
                setGraphic(badge);
            }
        });
        seatAttendanceCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Formats.enumLabel("attendance", c.getValue().attendanceStatus())));
    }

    // ------------------------------------------------------------------ helpers

    private static String describe(ExamOverview o) {
        return Formats.date(o.exam().examDate()) + "  ·  " + o.exam().periodName() + "  ·  "
                + o.exam().courseCode() + " — " + o.exam().courseName() + " (" + o.exam().gradeLevel() + ")";
    }

    private static String names(List<SupervisionRoster> staff) {
        return staff.stream().map(SupervisionRoster::teacherName).collect(Collectors.joining(", "));
    }

    private static boolean can(Permission p) {
        return UserSession.get().currentUser().map(u -> u.can(p)).orElse(false);
    }

    private static void show(Button b, boolean visible) {
        b.setVisible(visible);
        b.setManaged(visible);
    }

    private void fail(AppException e) {
        Alerts.error(Navigator.get().stage(), e);
    }

    /** Shows "Not assigned" in red when a room has no head / proctors yet. */
    private static final class MissingStaffCell extends TableCell<RoomAllocationSummary, String> {
        @Override
        protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            getStyleClass().remove("cell-missing");
            if (empty) {
                setText(null);
            } else if (value == null || value.isBlank()) {
                setText(Messages.get("ops.notAssigned"));
                getStyleClass().add("cell-missing");
            } else {
                setText(value);
            }
        }
    }
}
