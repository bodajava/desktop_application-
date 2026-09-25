package com.examhalls.controller;

import com.examhalls.model.CellStatus;
import com.examhalls.model.ExamPeriod;
import com.examhalls.model.ExamSchedule;
import com.examhalls.model.OccupancyCell;
import com.examhalls.model.OccupancyGrid;
import com.examhalls.model.Room;
import com.examhalls.model.RoomStatus;
import com.examhalls.model.SupervisionRoster;
import com.examhalls.service.OccupancyService;
import com.examhalls.service.ServiceRegistry;
import com.examhalls.ui.Alerts;
import com.examhalls.ui.BusyOverlay;
import com.examhalls.ui.FxAsync;
import com.examhalls.ui.Navigator;
import com.examhalls.ui.View;
import com.examhalls.util.Formats;
import com.examhalls.util.Messages;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/** Screen 8 — exam-day occupancy grid (rooms x periods) with under-staffing alerts. */
public class OccupancyGridController {

    @FXML private DatePicker datePicker;
    @FXML private HBox legend;
    @FXML private GridPane grid;
    @FXML private Label roomsValue;
    @FXML private Label studentsValue;
    @FXML private Label staffValue;
    @FXML private Label understaffedValue;
    @FXML private BusyOverlay busy;

    private final OccupancyService occupancy = ServiceRegistry.get().occupancyService();
    /** Set by {@link #showDate} (e.g. from the dashboard); wins over the default "next exam day". */
    private LocalDate requestedDate;

    @FXML
    private void initialize() {
        for (CellStatus s : CellStatus.values()) {
            Label chip = new Label(Formats.enumLabel("grid.status", s));
            chip.getStyleClass().addAll("legend-chip", "legend-" + css(s));
            legend.getChildren().add(chip);
        }
        datePicker.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                load(b);
            }
        });
        busy.show(Messages.get("busy.loading"));
        FxAsync.run(() -> occupancy.nextExamDate(LocalDate.now()).orElse(LocalDate.now()),
                d -> {
                    if (requestedDate == null) {
                        datePicker.setValue(d);
                    }
                }, e -> Alerts.error(Navigator.get().stage(), e), busy::hide);
    }

    /** Opens the grid on a specific day. */
    public void showDate(LocalDate date) {
        requestedDate = date;
        datePicker.setValue(date);
    }

    @FXML
    private void onPrev() {
        shift(-1);
    }

    @FXML
    private void onNext() {
        shift(1);
    }

    @FXML
    private void onNextExamDay() {
        LocalDate from = datePicker.getValue() == null ? LocalDate.now() : datePicker.getValue().plusDays(1);
        FxAsync.run(() -> occupancy.nextExamDate(from), d -> d.ifPresentOrElse(datePicker::setValue,
                        () -> Navigator.get().shell().toast(Messages.get("grid.noMoreExams"))),
                e -> Alerts.error(Navigator.get().stage(), e));
    }

    private void shift(int days) {
        if (datePicker.getValue() != null) {
            datePicker.setValue(datePicker.getValue().plusDays(days));
        }
    }

    private void load(LocalDate date) {
        busy.show(Messages.get("busy.loading"));
        FxAsync.run(() -> occupancy.getGrid(date), this::render, e -> Alerts.error(Navigator.get().stage(), e), busy::hide);
    }

    // ------------------------------------------------------------------ rendering

    private void render(OccupancyGrid g) {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        ColumnConstraints roomCol = new ColumnConstraints(170);
        grid.getColumnConstraints().add(roomCol);
        for (int i = 0; i < g.periods().size(); i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setHgrow(Priority.ALWAYS);
            c.setMinWidth(260);
            grid.getColumnConstraints().add(c);
        }

        Label corner = new Label(Messages.get("ops.col.room"));
        corner.getStyleClass().add("grid-header");
        corner.setMaxWidth(Double.MAX_VALUE);
        grid.add(corner, 0, 0);
        for (int p = 0; p < g.periods().size(); p++) {
            ExamPeriod period = g.periods().get(p);
            VBox head = new VBox(2, new Label(period.periodName()),
                    new Label(Formats.timeRange(period.startTime(), period.endTime())));
            head.getChildren().get(1).getStyleClass().add("muted");
            head.getStyleClass().add("grid-header");
            grid.add(head, p + 1, 0);
        }

        int row = 1;
        for (Room room : g.rooms()) {
            VBox roomBox = new VBox(2);
            Label code = new Label(room.roomCode());
            code.getStyleClass().add("grid-room-code");
            Label building = new Label(room.building());
            building.getStyleClass().add("muted");
            Label cap = new Label(Messages.get("grid.capacity", room.examCapacity()));
            cap.getStyleClass().add("muted");
            roomBox.getChildren().addAll(code, building, cap);
            if (room.status() != RoomStatus.AVAILABLE) {
                Label st = new Label(Formats.enumLabel("roomStatus", room.status()));
                st.getStyleClass().addAll("badge", "badge-blocked");
                roomBox.getChildren().add(st);
            }
            roomBox.getStyleClass().add("grid-room");
            grid.add(roomBox, 0, row);
            for (int p = 0; p < g.periods().size(); p++) {
                grid.add(cellView(g.cell(room.roomId(), g.periods().get(p).periodId())), p + 1, row);
            }
            row++;
        }

        int students = g.cells().values().stream().mapToInt(OccupancyCell::seated).sum();
        long used = g.cells().values().stream().filter(c -> c.seated() > 0).count();
        int staff = g.cells().values().stream().mapToInt(c -> c.staff().size()).sum();
        long under = g.count(CellStatus.UNDERSTAFFED);
        roomsValue.setText(String.valueOf(used));
        studentsValue.setText(String.valueOf(students));
        staffValue.setText(String.valueOf(staff));
        understaffedValue.setText(String.valueOf(under));
        understaffedValue.getStyleClass().remove("metric-alert");
        if (under > 0) {
            understaffedValue.getStyleClass().add("metric-alert");
        }
        if (!g.hasExams()) {
            Label none = new Label(Messages.get("grid.noExams"));
            none.getStyleClass().add("muted");
            grid.add(none, 1, row, Math.max(1, g.periods().size()), 1);
        }
    }

    private VBox cellView(OccupancyCell c) {
        VBox box = new VBox(6);
        box.getStyleClass().addAll("grid-cell", "grid-cell-" + css(c.status()));
        box.setMaxWidth(Double.MAX_VALUE);
        if (c.status() == CellStatus.EMPTY || c.status() == CellStatus.UNAVAILABLE) {
            Label l = new Label(Formats.enumLabel("grid.status", c.status()));
            l.getStyleClass().add("muted");
            box.getChildren().add(l);
            box.setAlignment(Pos.CENTER);
            return box;
        }

        Label courses = new Label(c.exams().stream().map(e -> e.courseCode() + " · " + e.gradeLevel())
                .collect(Collectors.joining("  +  ")));
        courses.getStyleClass().add("grid-course");

        ProgressBar bar = new ProgressBar(c.capacity() == 0 ? 0 : (double) c.seated() / c.capacity());
        bar.getStyleClass().add("occupancy-bar");
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        HBox occ = new HBox(8, bar, new Label(Formats.ratio(c.seated(), c.capacity())));
        occ.setAlignment(Pos.CENTER_LEFT);

        Label head = new Label(c.heads().isEmpty() ? Messages.get("grid.noHead")
                : Messages.get("grid.head", names(c.heads())));
        head.getStyleClass().add(c.heads().isEmpty() ? "text-danger" : "grid-staff");
        head.setWrapText(true);
        Label proctors = new Label(Messages.get("grid.proctors", c.proctors().size(), c.requiredProctors()));
        proctors.getStyleClass().add(c.proctors().size() < c.requiredProctors() ? "text-danger" : "grid-staff");

        Label badge = new Label(c.status() == CellStatus.UNDERSTAFFED
                ? Messages.get("grid.missing", c.missingPositions()) : Formats.enumLabel("grid.status", c.status()));
        badge.getStyleClass().addAll("badge", c.status() == CellStatus.UNDERSTAFFED ? "badge-blocked" : "badge-staffed");

        box.getChildren().addAll(courses, occ, head, proctors, badge);
        Tooltip.install(box, new Tooltip(tooltip(c)));
        box.setCursor(Cursor.HAND);
        long examId = c.exams().get(0).examId();
        box.setOnMouseClicked(e -> Navigator.get().shell()
                .<ExamOperationsController>navigate(View.EXAM_OPERATIONS, ctl -> ctl.openExam(examId)));
        return box;
    }

    private static String tooltip(OccupancyCell c) {
        StringBuilder b = new StringBuilder();
        for (ExamSchedule e : c.exams()) {
            b.append(e.courseCode()).append(" — ").append(e.courseName()).append('\n');
        }
        for (SupervisionRoster s : c.staff()) {
            b.append(Formats.enumLabel("supervisionRole", s.roleType())).append(": ").append(s.teacherName()).append('\n');
        }
        b.append(Messages.get("grid.openHint"));
        return b.toString();
    }

    private static String names(List<SupervisionRoster> staff) {
        return staff.stream().map(SupervisionRoster::teacherName).collect(Collectors.joining(", "));
    }

    private static String css(CellStatus s) {
        return s.name().toLowerCase().replace('_', '-');
    }
}
